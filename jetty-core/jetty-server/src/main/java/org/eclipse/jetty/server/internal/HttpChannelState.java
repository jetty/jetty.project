//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.jetty.http.ComplianceUtils;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Channel represents a sequence of request cycles from the same connection with only a single
 * request cycle may be active at once for each channel.
 * <p>
 * Many methods return {@link Runnable}s to indicate that further work is needed. These
 * can be given to an ExecutionStrategy instead of calling known methods like HttpChannel.handle().
 */
public class HttpChannelState implements HttpChannel, Components
{
    /**
     * The state of the written response
     */
    private enum StreamSendState
    {
        /** Last content not yet sent */
        SENDING,

        /** Last content sent, but send not yet completed */
        LAST_SENDING,

        /** Last content sent and completed */
        LAST_COMPLETE,

        /** Failing, so last send will never happen */
        FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelState.class);
    private static final Throwable NOTHING_TO_SEND = new Throwable("nothing_to_send");
    private static final HttpField SERVER_VERSION = new ResponseHttpFields.PersistentPreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new ResponseHttpFields.PersistentPreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
    private final LastWriteCallback _lastWriteCallback = new LastWriteCallback();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _readInvoker;
    private final SerializedInvoker _writeInvoker;
    private final ResponseHttpFields _responseHeaders = new ResponseHttpFields();
    private Thread _handling;
    private boolean _handled;
    private StreamSendState _streamSendState = StreamSendState.SENDING;
    private boolean _callbackCompleted = false;
    private ChannelRequest _request;
    private ChannelResponse _response;
    private long _oldIdleTimeout;
    private HttpStream _stream;
    private long _committedContentLength = -1;
    private Runnable _onContentAvailable;
    private Predicate<TimeoutException> _onIdleTimeout;
    private Content.Chunk _readFailure;
    private Consumer<Throwable> _onFailure;
    private Throwable _consumeAvailableFailure;
    private Throwable _writeFailure;
    private Throwable _lastWriteFailure;
    private Throwable _callbackFailure;
    private Attributes _cache;
    private boolean _expects100Continue;
    private ComplianceViolation.Listener _complianceViolationListener;

    public HttpChannelState(ConnectionMetaData connectionMetaData)
    {
        _connectionMetaData = connectionMetaData;
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _readInvoker = new HttpChannelSerializedInvoker(HttpChannelState.class.getSimpleName() + "_readInvoker", connectionMetaData.getConnector().getExecutor());
        _writeInvoker = new HttpChannelSerializedInvoker(HttpChannelState.class.getSimpleName() + "_writeInvoker", connectionMetaData.getConnector().getExecutor());
    }

    @Override
    public void initialize()
    {
        if (_complianceViolationListener == null)
        {
            List<ComplianceViolation.Listener> listeners = _connectionMetaData.getHttpConfiguration().getComplianceViolationListeners();
            _complianceViolationListener = switch (listeners.size())
            {
                case 0 -> ComplianceViolation.Listener.NOOP;
                case 1 -> listeners.get(0).initialize();
                default -> new InitializedCompositeComplianceViolationListener(listeners);
            };
        }

        if (!_connectionMetaData.getHttpConfiguration().isNotifyForbiddenComplianceViolations())
            _complianceViolationListener = new AllowedOnlyComplianceListener(_complianceViolationListener);
    }

    private static class AllowedOnlyComplianceListener implements ComplianceViolation.Listener
    {
        private final ComplianceViolation.Listener delegate;

        public AllowedOnlyComplianceListener(ComplianceViolation.Listener delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            if (event.allowed())
                delegate.onComplianceViolation(event);
        }

        @Override
        public ComplianceViolation.Listener initialize()
        {
            return delegate.initialize();
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            delegate.onRequestBegin(request);
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            delegate.onRequestEnd(request);
        }
    }

    @Override
    public ComplianceViolation.Listener getComplianceViolationListener()
    {
        return _complianceViolationListener;
    }

    @Override
    public void recycle()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("recycling {}", this);

            // Break the link between request and channel, so that
            // applications cannot use request/response/callback anymore.
            _request._httpChannelState = null;

            // Recycle.
            _responseHeaders.recycle();
            _handling = null;
            _handled = false;
            _streamSendState = StreamSendState.SENDING;
            _callbackCompleted = false;
            _request = null;
            _response = null;
            _oldIdleTimeout = 0;
            // Break the link between channel and stream.
            _stream = null;
            _committedContentLength = -1;
            _onContentAvailable = null;
            _onIdleTimeout = null;
            _readFailure = null;
            _onFailure = null;
            _callbackFailure = null;
            _expects100Continue = false;
            _complianceViolationListener = null;
        }
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _connectionMetaData.getHttpConfiguration();
    }

    public HttpStream getHttpStream()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _stream;
        }
    }

    public void setHttpStream(HttpStream stream)
    {
        try (AutoLock ignored = _lock.lock())
        {
            _stream = stream;
        }
    }

    public Server getServer()
    {
        return _connectionMetaData.getConnector().getServer();
    }

    @Override
    public ConnectionMetaData getConnectionMetaData()
    {
        return _connectionMetaData;
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return getConnectionMetaData().getConnector().getByteBufferPool();
    }

    @Override
    public Scheduler getScheduler()
    {
        return getServer().getScheduler();
    }

    @Override
    public ThreadPool getThreadPool()
    {
        Executor executor = getExecutor();
        if (executor instanceof ThreadPool threadPool)
            return threadPool;
        return new ThreadPoolWrapper(executor);
    }

    @Override
    public Executor getExecutor()
    {
        return VirtualThreads.getExecutor(getServer().getThreadPool());
    }

    @Override
    public Attributes getCache()
    {
        if (_cache == null)
        {
            if (getConnectionMetaData().isPersistent())
                _cache = new Attributes.Mapped();
            else
                _cache = Attributes.NULL;
        }
        return _cache;
    }

    /**
     * Start request handling by returning a Runnable that will call {@link Handler#handle(Request, Response, Callback)}.
     *
     * @param request The request metadata to handle.
     * @return A Runnable that will call {@link Handler#handle(Request, Response, Callback)}.  Unlike all other {@link Runnable}s
     * returned by HttpChannel methods, this runnable should not be mutually excluded or serialized. Specifically
     * other {@link Runnable}s returned by methods on this class can be run concurrently with the {@link Runnable}
     * returned from this method.
     */
    public Runnable onRequest(MetaData.Request request)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onRequest {} {}", request, this);

        try (AutoLock ignored = _lock.lock())
        {
            if (_stream == null)
                throw new IllegalStateException("No HttpStream");
            if (_request != null)
                throw new IllegalStateException("duplicate request");
            initialize();
            _request = new ChannelRequest(this, request);
            _response = new ChannelResponse(_request);
            _expects100Continue = request.is100ContinueExpected();

            HttpConfiguration httpConfiguration = getHttpConfiguration();
            HttpFields.Mutable responseHeaders = _response.getHeaders();
            if (httpConfiguration.getSendServerVersion())
                responseHeaders.add(SERVER_VERSION);
            if (httpConfiguration.getSendXPoweredBy())
                responseHeaders.add(POWERED_BY);
            if (httpConfiguration.getSendDateHeader())
                responseHeaders.add(getConnectionMetaData().getConnector().getServer().getDateField());

            long idleTO = httpConfiguration.getIdleTimeout();
            _oldIdleTimeout = _stream.getIdleTimeout();
            if (idleTO >= 0 && _oldIdleTimeout != idleTO)
                _stream.setIdleTimeout(idleTO);

            // This is deliberately not serialized to allow a handler to block.
            return _handlerInvoker;
        }
    }

    public Request getRequest()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request;
        }
    }

    public Response getResponse()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _response;
        }
    }

    public Runnable onContentAvailable()
    {
        Runnable onContent;
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return null;
            onContent = _onContentAvailable;
            _onContentAvailable = null;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("onContentAvailable {} {}", onContent, this);
        return _readInvoker.offer(onContent);
    }

    @Override
    public InvocationType getInvocationType()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return HttpChannel.super.getInvocationType();
            return Invocable.getInvocationType(_onContentAvailable);
        }
    }

    @Override
    public IdleTimeoutTask onIdleTimeout(TimeoutException t)
    {
        boolean requestHandled;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onIdleTimeout {}", this, t);

            // Either too early or too late.
            if (_stream == null || _request == null)
                return new IdleTimeoutTask(null, false);

            requestHandled = _handling != null || _handled;

            Runnable invokeOnContentAvailable = null;
            if (_readFailure == null)
            {
                // If there is demand, take the onContentAvailable runnable to invoke below.
                invokeOnContentAvailable = _onContentAvailable;
                _onContentAvailable = null;

                // If there was demand, then arrange for the next read to return a transient chunk failure.
                if (invokeOnContentAvailable != null)
                    _readFailure = Content.Chunk.from(t, false);
            }

            // If a write call is pending, take the writeCallback to fail below.
            Runnable invokeWriteFailure = _response.lockedFailWrite(t);

            // If there was a pending IO operation, deliver the idle timeout via them.
            if (invokeOnContentAvailable != null || invokeWriteFailure != null)
                return new IdleTimeoutTask(Invocable.combine(_readInvoker.offer(invokeOnContentAvailable), _writeInvoker.offer(invokeWriteFailure)), requestHandled);

            // Otherwise, if there are idle timeout listeners, ask them whether we should call onFailure.
            Predicate<TimeoutException> onIdleTimeout = _onIdleTimeout;
            if (onIdleTimeout != null)
            {
                return new IdleTimeoutTask(() ->
                {
                    boolean failure;
                    try
                    {
                        failure = onIdleTimeout.test(t);
                    }
                    catch (Throwable x)
                    {
                        ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                        failure = true;
                    }
                    if (failure)
                    {
                        // If the idle timeout listener(s) returns true or throws,
                        // then we call onFailure and run any task it returns.
                        Runnable task = onFailure(t);
                        if (task != null)
                            task.run();
                    }
                }, requestHandled);
            }
        }

        // Otherwise treat as a failure.
        return new IdleTimeoutTask(onFailure(t), requestHandled);
    }

    @Override
    public Runnable onFailure(Throwable x)
    {
        return onFailure(x, false);
    }

    @Override
    public Runnable onRemoteFailure(Throwable x)
    {
        return onFailure(x, true);
    }

    private Runnable onFailure(Throwable x, boolean remote)
    {
        HttpStream stream;
        Runnable task;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onFailure remote={} {}", remote, this, x);

            // If the channel doesn't have a stream, then the error is ignored.
            stream = _stream;
            if (stream == null)
                return null;

            if (_request == null)
            {
                // If the channel doesn't have a request, then the error must have occurred during the parsing of
                // the request line / headers, so make a temp request for logging and producing an error response.
                MetaData.Request errorRequest = new MetaData.Request("GET", HttpURI.from("/badRequest"), HttpVersion.HTTP_1_0, HttpFields.EMPTY);
                _request = new ChannelRequest(this, errorRequest);
                _response = new ChannelResponse(_request);
            }

            // If not handled, then we just fail the request callback
            if (!_handled && _handling == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failing request not yet handled {} {}", _request, this);
                Callback callback = _request._callback;
                task = () -> callback.failed(x);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failing request {} {}", _request, this);

                // Set the failure to arrange for any subsequent reads or demands to fail.
                if (_readFailure == null)
                    _readFailure = Content.Chunk.from(x, true);
                else
                    ExceptionUtil.addSuppressedIfNotAssociated(_readFailure.getFailure(), x);

                // If there is demand, take the onContentAvailable runnable to invoke below.
                Runnable invokeOnContentAvailable = _onContentAvailable;
                _onContentAvailable = null;

                // If a write call is in progress, take the writeCallback to fail below.
                Runnable invokeWriteFailure = _response.lockedFailWrite(x);

                // Notify the failure listeners only once.
                Consumer<Throwable> onFailure = _onFailure;
                _onFailure = null;

                boolean noFailureListener = onFailure == null;
                boolean skipListeners = remote && !getHttpConfiguration().isNotifyRemoteAsyncErrors();
                boolean readerOrWriterWaiting = invokeOnContentAvailable != null || invokeWriteFailure != null;
                Runnable invokeOnFailureListeners = noFailureListener || readerOrWriterWaiting || skipListeners ? null : () ->
                {
                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invoking failure listeners {} {}", HttpChannelState.this, onFailure, x);
                        onFailure.accept(x);
                    }
                    catch (Throwable throwable)
                    {
                        ExceptionUtil.addSuppressedIfNotAssociated(x, throwable);
                    }
                };

                // Serialize all the error actions, keep each call on a separate line to help with debugging.
                task = Invocable.combine(
                    _readInvoker.offer(invokeOnContentAvailable),
                    _writeInvoker.offer(invokeWriteFailure),
                    _readInvoker.offer(invokeOnFailureListeners)
                );
            }
        }

        // Consume content as soon as possible to open any
        // flow control window and release any request buffer.
        Throwable unconsumed = stream.consumeAvailable();
        if (unconsumed != null && LOG.isDebugEnabled())
            LOG.debug("consuming content during error {}", unconsumed.toString());

        return task;
    }

    @Override
    public Runnable onClose()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onClose {} stream={}", this, _stream);

            // If the channel doesn't have a stream, then no action is needed.
            if (_stream == null)
                return null;
        }
        return onFailure(new EofException());
    }

    public void addHttpStreamWrapper(Function<HttpStream, HttpStream> onStreamEvent)
    {
        while (true)
        {
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
            }
            if (stream == null)
                throw new IllegalStateException("No active stream");
            HttpStream combined = onStreamEvent.apply(stream);
            if (combined == null)
                throw new IllegalArgumentException("Cannot remove stream");
            if (combined == stream)
                return;
            try (AutoLock ignored = _lock.lock())
            {
                if (_stream != stream)
                    continue;
                _stream = combined;
                break;
            }
        }
    }

    private void resetResponse()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_responseHeaders.isCommitted())
                throw new IllegalStateException("response committed");
            _responseHeaders.clear();
        }
    }

    private Throwable lockedStreamSend(boolean last, long length)
    {
        assert _lock.isHeldByCurrentThread();

        return switch (_streamSendState)
        {
            case SENDING ->
            {
                _streamSendState = last ? StreamSendState.LAST_SENDING : StreamSendState.SENDING;
                yield null;
            }

            // There are many instances of code that wants to ensure the output is closed, so
            // it does a redundant write(true, callback). Other code may do a write(false, callback) to ensure
            // they are flushed. The DO_NOT_SEND option supports these by turning such writes into a NOOP.
            case LAST_SENDING, LAST_COMPLETE -> (length > 0)
                ? new IllegalStateException("last already written")
                : NOTHING_TO_SEND;

            case FAILED -> null;
        };
    }

    private void lockedStreamSendCompleted(boolean success)
    {
        assert _lock.isHeldByCurrentThread();
        if (_streamSendState == StreamSendState.LAST_SENDING)
            _streamSendState = success ? StreamSendState.LAST_COMPLETE : StreamSendState.SENDING;
    }

    private boolean lockedIsLastStreamSendCompleted()
    {
        assert _lock.isHeldByCurrentThread();
        return _streamSendState == StreamSendState.LAST_COMPLETE || _streamSendState == StreamSendState.FAILED;
    }

    private boolean lockedLastStreamSend()
    {
        assert _lock.isHeldByCurrentThread();
        if (_streamSendState != StreamSendState.SENDING)
            return false;

        _streamSendState = StreamSendState.LAST_SENDING;
        return true;
    }

    private Throwable lockedGetCompleteStreamFailure()
    {
        assert _lock.isHeldByCurrentThread();

        Throwable completeStreamFailure = ExceptionUtil.combine(_lastWriteFailure,
            ExceptionUtil.combine(_writeFailure, _consumeAvailableFailure));
        // Check the case for a committed response with a
        // non-last successful write, then throwing an exception.
        if (_streamSendState == StreamSendState.FAILED)
            completeStreamFailure = ExceptionUtil.combine(completeStreamFailure, _callbackFailure);

        return completeStreamFailure;
    }

    @Override
    public String toString()
    {
        try (AutoLock lock = _lock.tryLock())
        {
            String held = lock.isHeldByCurrentThread() ? "" : "?";
            return String.format("%s@%x[%s:handling=%s,handled=%s,send=%s,completed=%s,request=%s]",
                TypeUtil.toShortName(this.getClass()),
                hashCode(),
                held,
                _handling,
                _handled,
                _streamSendState,
                _callbackCompleted,
                _request
            );
        }
    }

    /**
     * Reminder: when a stream is failed, it aborts the connection.
     */
    private void completeStream(HttpStream stream, Throwable failure)
    {
        try
        {
            RequestLog requestLog = getServer().getRequestLog();
            if (requestLog != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("logging {}", HttpChannelState.this);

                requestLog.log(_request.getLoggedRequest(), _response);
            }

            // Clean up any multipart tmp files and release any associated resources.
            MultiPartFormData.Parts parts = MultiPartFormData.getParts(_request);
            if (parts != null)
                parts.close();

            long idleTO = getHttpConfiguration().getIdleTimeout();
            if (idleTO > 0 && _oldIdleTimeout != idleTO)
                stream.setIdleTimeout(_oldIdleTimeout);
        }
        finally
        {
            ComplianceViolation.Listener listener = getComplianceViolationListener();
            listener.onRequestEnd(_request);

            // This is THE ONLY PLACE the stream is succeeded or failed.
            if (LOG.isDebugEnabled())
                LOG.debug("completing the stream of {}", this, failure);
            if (failure == null)
                stream.succeeded();
            else
                stream.failed(failure);
        }
    }

    private class HandlerInvoker implements Task
    {
        @Override
        public void run()
        {
            // Once we switch to HANDLING state and beyond, then we assume that the
            // application will call the callback, and thus any onFailure reports will not.
            // However, if a thread calling the application throws, then that exception will be reported
            // to the callback.
            ChannelRequest request;
            ChannelResponse response;
            try (AutoLock ignored = _lock.lock())
            {
                assert _handling == null && !_handled;
                _handling = Thread.currentThread();
                request = _request;
                response = _response;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("invoking handler in {}", HttpChannelState.this);
            Server server = _connectionMetaData.getConnector().getServer();

            try
            {
                // Customize before processing.
                HttpConfiguration httpConfiguration = getHttpConfiguration();

                Request customized = request;
                HttpFields.Mutable responseHeaders = response.getHeaders();
                for (HttpConfiguration.Customizer customizer : httpConfiguration.getCustomizers())
                {
                    Request next = customizer.customize(customized, responseHeaders);
                    customized = next == null ? customized : next;
                }

                if (customized != request && server.getRequestLog() != null)
                    request.setLoggedRequest(customized);

                String pathInContext = Request.getPathInContext(customized);
                if (pathInContext != null && !pathInContext.startsWith("/"))
                {
                    String method = customized.getMethod();
                    if (!HttpMethod.PRI.is(method) && !HttpMethod.CONNECT.is(method) && !HttpMethod.OPTIONS.is(method))
                        throw new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "Bad URI path");
                }

                ComplianceViolation.Listener listener = getComplianceViolationListener();
                listener.onRequestBegin(customized);

                HttpURI uri = customized.getHttpURI();
                if (uri.hasViolations())
                {
                    UriCompliance uriCompliance = httpConfiguration.getUriCompliance();
                    ComplianceUtils.verify(uriCompliance, uri, listener, msg -> new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, msg));
                }

                HttpCompliance httpCompliance = httpConfiguration.getHttpCompliance();
                ComplianceUtils.verify(customized.getHttpURI(), customized.getHeaders(), httpCompliance, listener);

                if (!server.handle(customized, response, request._callback))
                    Response.writeError(customized, response, request._callback, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                request._callback.failed(t);
            }

            HttpStream stream;
            Throwable completeStreamFailure;
            boolean completeStream;
            boolean callbackCompleted;
            boolean lastStreamSendComplete;

            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                _handling = null;
                _handled = true;
                callbackCompleted = _callbackCompleted;
                lastStreamSendComplete = lockedIsLastStreamSendCompleted();
                completeStream = callbackCompleted && lastStreamSendComplete;
                completeStreamFailure = lockedGetCompleteStreamFailure();

                if (LOG.isDebugEnabled())
                    LOG.debug("handler invoked: completeStream={} failure={} callbackCompleted={} {}", completeStream, completeStreamFailure, callbackCompleted, HttpChannelState.this);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("stream={}, failure={}, callbackCompleted={}, completeStream={}", stream, completeStreamFailure, callbackCompleted, completeStream);

            if (completeStream)
                completeStream(stream, completeStreamFailure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getConnectionMetaData().getConnector().getServer().getInvocationType();
        }
    }

    private class LastWriteCallback implements Callback
    {
        /**
         * Called only as {@link Callback} by last write from {@link ChannelCallback#succeeded}
         */
        @Override
        public void succeeded()
        {
            completeLastWrite(null);
        }

        /**
         * Called only as {@link Callback} by last send from {@link ChannelCallback#succeeded}
         */
        @Override
        public void failed(Throwable failure)
        {
            completeLastWrite(failure);
        }

        private void completeLastWrite(Throwable failure)
        {
            HttpStream stream;
            boolean completeStream;
            Throwable completeStreamFailure;
            try (AutoLock ignored = _lock.lock())
            {
                assert _callbackCompleted;
                _streamSendState = failure == null ? StreamSendState.LAST_COMPLETE : StreamSendState.FAILED;
                completeStream = _handling == null; // if we have not handled yet or have completed handling
                stream = _stream;
                _lastWriteFailure = failure;
                completeStreamFailure = lockedGetCompleteStreamFailure();
            }

            if (completeStream)
                completeStream(stream, completeStreamFailure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }

    public static class ChannelRequest extends Attributes.Lazy implements Request
    {
        private final long _headersNanoTime = NanoTime.now();
        private final ChannelCallback _callback = new ChannelCallback(this);
        private final String _id;
        private final ConnectionMetaData _connectionMetaData;
        private final MetaData.Request _metaData;
        private final AutoLock _lock;
        private final LongAdder _contentBytesRead = new LongAdder();
        private HttpChannelState _httpChannelState;
        private Request _loggedRequest;
        private HttpFields _trailers;
        private Throwable _consumeAvailableFailure;

        ChannelRequest(HttpChannelState httpChannelState, MetaData.Request metaData)
        {
            _httpChannelState = Objects.requireNonNull(httpChannelState);
            _id = httpChannelState.getHttpStream().getId(); // Copy ID now, as stream will ultimately be nulled
            _connectionMetaData = httpChannelState.getConnectionMetaData();
            _metaData = Objects.requireNonNull(metaData);
            _lock = httpChannelState._lock;
        }

        public void setLoggedRequest(Request request)
        {
            _loggedRequest = request;
        }

        public Request getLoggedRequest()
        {
            return _loggedRequest == null ? this : _loggedRequest;
        }

        HttpStream getHttpStream()
        {
            return getHttpChannelState()._stream;
        }

        public long getContentBytesRead()
        {
            return _contentBytesRead.longValue();
        }

        @Override
        public String getId()
        {
            return _id;
        }

        @Override
        public Components getComponents()
        {
            return getHttpChannelState();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _connectionMetaData;
        }

        private HttpChannelState getHttpChannelState()
        {
            try (AutoLock ignore = _lock.lock())
            {
                return lockedGetHttpChannelState();
            }
        }

        private HttpChannelState lockedGetHttpChannelState()
        {
            assert _lock.isHeldByCurrentThread();
            if (_httpChannelState == null)
                throw new IllegalStateException("channel already completed");
            return _httpChannelState;
        }

        @Override
        public String getMethod()
        {
            return _metaData.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _metaData.getHttpURI();
        }

        @Override
        public Context getContext()
        {
            return getConnectionMetaData().getConnector().getServer().getContext();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _metaData.getHttpFields();
        }

        @Override
        public HttpFields getTrailers()
        {
            return _trailers;
        }

        @Override
        public long getBeginNanoTime()
        {
            return _metaData.getBeginNanoTime();
        }

        @Override
        public long getHeadersNanoTime()
        {
            return _headersNanoTime;
        }

        @Override
        public boolean isSecure()
        {
            return HttpScheme.HTTPS.is(getHttpURI().getScheme());
        }

        @Override
        public long getLength()
        {
            return _metaData.getContentLength();
        }

        @Override
        public Content.Chunk read()
        {
            try
            {
                HttpStream stream;
                boolean expecting100;
                HttpChannelState httpChannel;
                try (AutoLock ignored = _lock.lock())
                {
                    httpChannel = lockedGetHttpChannelState();
                    Content.Chunk error = httpChannel._readFailure;
                    httpChannel._readFailure = Content.Chunk.next(error);
                    if (error != null)
                        return error;

                    stream = httpChannel._stream;
                    expecting100 = httpChannel._expects100Continue;
                }
                Content.Chunk chunk = stream.read();

                if (LOG.isDebugEnabled())
                    LOG.debug("read {}", chunk);

                if (chunk == null)
                    return null;

                if (expecting100)
                {
                    // No need to send 100 continues as content has already arrived
                    try (AutoLock ignored = _lock.lock())
                    {
                        httpChannel._expects100Continue = false;
                    }
                }

                if (chunk.hasRemaining())
                    _contentBytesRead.add(chunk.remaining());

                if (chunk instanceof Trailers trailers)
                    _trailers = trailers.getTrailers();

                return chunk;
            }
            catch (Throwable t)
            {
                return Content.Chunk.from(t, true);
            }
        }

        @Override
        public boolean consumeAvailable()
        {
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();
                HttpStream stream = httpChannel._stream;
                Throwable failure = stream.consumeAvailable();
                httpChannel._consumeAvailableFailure = failure;
                return failure == null;
            }
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            boolean error;
            HttpStream stream;
            HttpChannelState httpChannelState;
            InterimCallback interimCallback = null;
            try (AutoLock ignored = _lock.lock())
            {
                httpChannelState = lockedGetHttpChannelState();
                stream = httpChannelState._stream;
                error = httpChannelState._readFailure != null;

                if (LOG.isDebugEnabled())
                    LOG.debug("demand {}", httpChannelState);

                if (!error)
                {
                    if (httpChannelState._onContentAvailable != null)
                        throw new IllegalArgumentException("demand pending");
                    httpChannelState._onContentAvailable = demandCallback;

                    if (httpChannelState._expects100Continue && httpChannelState._response._writeCallback == null)
                    {
                        httpChannelState._response._writeCallback = interimCallback = new InterimCallback(httpChannelState);
                        httpChannelState._expects100Continue = false;
                    }
                }
            }

            if (error)
            {
                httpChannelState._readInvoker.run(demandCallback);
            }
            else if (interimCallback == null)
            {
                stream.demand();
            }
            else
            {
                stream.send(_metaData, new MetaData.Response(HttpStatus.CONTINUE_100, null, getConnectionMetaData().getHttpVersion(), HttpFields.EMPTY), false, null, interimCallback);
                interimCallback.whenComplete((v, t) -> stream.demand());
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            ThreadPool.executeImmediately(getContext(), _httpChannelState.onFailure(failure));
        }

        @Override
        public void push(MetaData.Request resource)
        {
            getHttpStream().push(resource);
        }

        @Override
        public void addIdleTimeoutListener(Predicate<TimeoutException> onIdleTimeout)
        {
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();

                if (httpChannel._readFailure != null)
                    return;

                if (httpChannel._onIdleTimeout == null)
                {
                    httpChannel._onIdleTimeout = onIdleTimeout;
                }
                else
                {
                    Predicate<TimeoutException> previous = httpChannel._onIdleTimeout;
                    httpChannel._onIdleTimeout = throwable ->
                    {
                        if (!previous.test(throwable))
                            return onIdleTimeout.test(throwable);
                        return true;
                    };
                }
            }
        }

        @Override
        public void addFailureListener(Consumer<Throwable> onFailure)
        {
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();

                if (httpChannel._readFailure != null)
                    return;

                if (httpChannel._onFailure == null)
                {
                    httpChannel._onFailure = onFailure;
                }
                else
                {
                    Consumer<Throwable> previous = httpChannel._onFailure;
                    httpChannel._onFailure = throwable ->
                    {
                        try
                        {
                            previous.accept(throwable);
                        }
                        catch (Throwable t)
                        {
                            ExceptionUtil.addSuppressedIfNotAssociated(throwable, t);
                        }
                        finally
                        {
                            onFailure.accept(throwable);
                        }
                    };
                }
            }
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return getHttpStream().getTunnelSupport();
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
            getHttpChannelState().addHttpStreamWrapper(wrapper);
        }

        @Override
        public Session getSession(boolean create)
        {
            return null;
        }

        @Override
        public int hashCode()
        {
            // Override the implementation from the base class,
            // and align with the implementation of Request.Wrapper.
            return System.identityHashCode(this);
        }

        @Override
        public boolean equals(Object obj)
        {
            // Override the implementation from the base class,
            // and align with the implementation of Request.Wrapper.
            return this == obj;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x %s %s", getMethod(), hashCode(), getHttpURI(), _metaData.getHttpVersion());
        }
    }

    /**
     * The Channel's implementation of the {@link Response} API.
     * Also is a {@link Callback} used by the {@link #write(boolean, ByteBuffer, Callback)}
     * method when calling
     * {@link HttpStream#send(MetaData.Request, MetaData.Response, boolean, ByteBuffer, Callback)}
     */
    public static class ChannelResponse implements Response, Callback
    {
        private final ChannelRequest _request;
        private final ResponseHttpFields _httpFields;
        protected int _status;
        private long _contentBytesWritten;
        private Supplier<HttpFields> _trailers;
        private Callback _writeCallback;

        private ChannelResponse(ChannelRequest request)
        {
            _request = request;
            _httpFields = getResponseHttpFields(_request.lockedGetHttpChannelState());
        }

        protected ResponseHttpFields getResponseHttpFields(HttpChannelState httpChannelState)
        {
            return httpChannelState._responseHeaders;
        }

        protected ResponseHttpFields getResponseHttpFields()
        {
            return _httpFields;
        }

        private boolean lockedIsWriting()
        {
            assert _request._lock.isHeldByCurrentThread();
            return _writeCallback != null;
        }

        private Runnable lockedFailWrite(Throwable x)
        {
            assert _request._lock.isHeldByCurrentThread();
            Callback writeCallback = _writeCallback;
            if (writeCallback == null)
                return null;
            _writeCallback = null;

            Runnable cancellation = _request.getHttpStream().cancelSend(x, writeCallback);
            HttpChannelState httpChannelState = _request._httpChannelState;
            httpChannelState._writeFailure = ExceptionUtil.combine(httpChannelState._writeFailure, x);
            return cancellation;
        }

        public long getContentBytesWritten()
        {
            return _contentBytesWritten;
        }

        @Override
        public Request getRequest()
        {
            return _request;
        }

        @Override
        public int getStatus()
        {
            return _status;
        }

        @Override
        public void setStatus(int code)
        {
            if (code < 100 || code > 999)
                throw new IllegalArgumentException();
            if (!isCommitted())
                _status = code;
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _httpFields;
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                return _trailers;
            }
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                _trailers = trailers;
            }
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback)
        {
            Callback writeCallback = Objects.requireNonNullElse(callback, NOOP);

            long length = BufferUtil.length(content);

            HttpChannelState httpChannelState;
            HttpStream stream;
            Throwable writeFailure;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.lockedGetHttpChannelState();
                long totalWritten = _contentBytesWritten + length;
                writeFailure = httpChannelState._writeFailure;

                if (writeFailure == null)
                {
                    if (_writeCallback != null)
                    {
                        if (_writeCallback instanceof InterimCallback interimCallback)
                        {
                            // Do this write after the interim callback.
                            interimCallback.whenComplete((v, t) -> write(last, content, writeCallback));
                            return;
                        }
                        writeFailure = new WritePendingException();
                    }
                    else
                    {
                        long committedContentLength = httpChannelState._committedContentLength;
                        long contentLength = committedContentLength >= 0 ? committedContentLength : getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);

                        if (contentLength >= 0 && totalWritten != contentLength &&
                            !(totalWritten == 0 && (HttpMethod.HEAD.is(_request.getMethod()) || getStatus() == HttpStatus.NOT_MODIFIED_304)))
                        {
                            // If the content length were not compatible with what was written, then we need to abort.
                            String lengthError = null;
                            if (totalWritten > contentLength)
                                lengthError = "written %d > %d content-length";
                            else if (last && !(totalWritten == 0 && HttpMethod.HEAD.is(_request.getMethod())))
                                lengthError = "written %d < %d content-length";
                            if (lengthError != null)
                            {
                                String message = lengthError.formatted(totalWritten, contentLength);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("fail {} {}", writeCallback, message);
                                writeFailure = new IOException(message);
                            }
                        }
                    }
                }

                // If no failure by this point, we can try to switch to sending state.
                if (writeFailure == null)
                    writeFailure = httpChannelState.lockedStreamSend(last, length);

                if (writeFailure == NOTHING_TO_SEND)
                {
                    httpChannelState._writeInvoker.run(Invocable.from(writeCallback.getInvocationType(), writeCallback::succeeded));
                    return;
                }
                // Have we failed in some way?
                if (writeFailure != null)
                {
                    httpChannelState._lastWriteFailure = writeFailure;
                    Throwable failure = writeFailure;
                    httpChannelState._writeInvoker.run(() -> HttpChannelState.failed(writeCallback, failure));
                    return;
                }

                // No failure, do the actual stream send using the ChannelResponse as the callback.
                _writeCallback = writeCallback;
                _contentBytesWritten = totalWritten;
                stream = httpChannelState._stream;
                if (_httpFields.commit())
                    responseMetaData = lockedPrepareResponse(httpChannelState, last);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("writing last={} {} {}", last, BufferUtil.toDetailString(content), this);
            stream.send(_request._metaData, responseMetaData, last, content, this);
        }

        /**
         * Called when the call to
         * {@link HttpStream#send(MetaData.Request, MetaData.Response, boolean, ByteBuffer, Callback)}
         * made by {@link ChannelResponse#write(boolean, ByteBuffer, Callback)} succeeds.
         * The implementation maintains the {@link #_streamSendState} before taking
         * and serializing the call to the {@link #_writeCallback}, which was set by the call to {@code write}.
         */
        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write succeeded {}", this);
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                callback = _writeCallback;
                _writeCallback = null;
                httpChannel = _request.lockedGetHttpChannelState();
                httpChannel.lockedStreamSendCompleted(true);
            }
            if (callback != null)
                httpChannel._writeInvoker.run(Invocable.from(callback.getInvocationType(), callback::succeeded));
        }

        /**
         * Called when the call to
         * {@link HttpStream#send(MetaData.Request, MetaData.Response, boolean, ByteBuffer, Callback)}
         * made by {@link ChannelResponse#write(boolean, ByteBuffer, Callback)} fails.
         * <p>
         * The implementation maintains the {@link #_streamSendState} before taking
         * and serializing the call to the {@link #_writeCallback}, which was set by the call to {@code write}.
         *
         * @param x The reason for the failure.
         */
        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write failed {}", this, x);
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                callback = _writeCallback;
                _writeCallback = null;
                httpChannel = _request.lockedGetHttpChannelState();
                httpChannel.lockedStreamSendCompleted(false);
            }
            if (callback != null)
                httpChannel._writeInvoker.run(() -> HttpChannelState.failed(callback, x));
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_writeCallback);
        }

        @Override
        public boolean isCommitted()
        {
            return _httpFields.isCommitted();
        }

        @Override
        public boolean hasLastWrite()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                if (_request._httpChannelState == null)
                    return true;

                return _request._httpChannelState._streamSendState != StreamSendState.SENDING;
            }
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                if (_request._httpChannelState == null)
                    return false;

                return _request._httpChannelState._callbackCompleted && _request._httpChannelState._callbackFailure == null;
            }
        }

        @Override
        public void reset()
        {
            _status = 0;
            _trailers = null;
            _contentBytesWritten = 0;
            _request.getHttpChannelState().resetResponse();
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            if (!HttpStatus.isInterim(status))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid interim status code: " + status));

            HttpStream stream;
            MetaData.Response response;
            InterimCallback interimCallback;
            try (AutoLock ignored = _request._lock.lock())
            {
                HttpChannelState httpChannelState = _request.lockedGetHttpChannelState();
                stream = httpChannelState._stream;

                if (status == HttpStatus.CONTINUE_100)
                {
                    if (!httpChannelState._expects100Continue)
                        return CompletableFuture.failedFuture(new IllegalStateException("100 not expected"));
                    if (_request.getLength() == 0)
                        return CompletableFuture.completedFuture(null);
                    httpChannelState._expects100Continue = false;
                }

                if (_httpFields.isCommitted())
                    return CompletableFuture.failedFuture(new IllegalStateException("Committed"));
                if (_writeCallback != null)
                    return CompletableFuture.failedFuture(new WritePendingException());

                _writeCallback = interimCallback = new InterimCallback(httpChannelState);
                HttpVersion version = httpChannelState.getConnectionMetaData().getHttpVersion();
                response = new MetaData.Response(status, null, version, headers);
            }

            stream.send(_request._metaData, response, false, null, interimCallback);
            return interimCallback;
        }

        MetaData.Response lockedPrepareResponse(HttpChannelState httpChannel, boolean last)
        {
            assert _request._lock.isHeldByCurrentThread();

            // Assume 200 unless told otherwise.
            if (_status == 0)
                _status = HttpStatus.OK_200;

            // Can we set the content length?
            HttpFields.Mutable mutableHeaders = _httpFields.getMutableHttpFields();
            httpChannel._committedContentLength = mutableHeaders.getLongField(HttpHeader.CONTENT_LENGTH);
            if (last && httpChannel._committedContentLength < 0L)
            {
                httpChannel._committedContentLength = _contentBytesWritten;
                mutableHeaders.put(HttpHeader.CONTENT_LENGTH, httpChannel._committedContentLength);
            }

            httpChannel._stream.prepareResponse(mutableHeaders);

            return new MetaData.Response(
                _status, null, httpChannel.getConnectionMetaData().getHttpVersion(),
                _httpFields,
                httpChannel._committedContentLength,
                getTrailersSupplier()
            );
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s,%s}".formatted(TypeUtil.toShortName(this.getClass()), hashCode(), getStatus(), getRequest());
        }
    }

    private static class ChannelCallback implements Callback
    {
        private final ChannelRequest _request;
        private Throwable _completedBy;

        private ChannelCallback(ChannelRequest request)
        {
            _request = request;
        }

        /**
         * Called when the {@link Handler} (or it's delegates) succeeds the request handling.
         */
        @Override
        public void succeeded()
        {
            completeChannelCallback(null);
        }

        /**
         * Called when the {@link Handler} (or it's delegates) fail the request handling.
         *
         * @param failure The reason for the failure.
         */
        @Override
        public void failed(Throwable failure)
        {
            completeChannelCallback(failure);
        }

        private void completeChannelCallback(Throwable failure)
        {
            // Called when the request/response cycle is completing.
            HttpStream stream;
            boolean doLastStreamSend = false;
            HttpChannelState httpChannelState;
            ChannelRequest request;
            ChannelResponse response;
            MetaData.Response responseMetaData = null;
            boolean completeStream = false;
            ErrorResponse errorResponse = null;
            Callback writeCallback = null;
            Throwable completeStreamFailure;

            try (AutoLock ignored = _request._lock.lock())
            {
                if (lockedCompleteCallback())
                    return;

                request = _request;
                httpChannelState = _request._httpChannelState;
                response = httpChannelState._response;
                stream = httpChannelState._stream;
                assert httpChannelState._callbackFailure == null;

                // Turn pending demand into failure.
                if (httpChannelState._onContentAvailable != null)
                {
                    failure = ExceptionUtil.combine(failure, new IllegalStateException("demand pending"));
                }
                else
                {
                    // If consumeAvailable() cannot consume all the content, it returns an exception
                    // and may also make the connection non-persistent.
                    // This must not result in an error according to RFC2616 section 8.2.3.
                    // Also, consumeAvailable must be called even when the connection is not
                    // persistent otherwise RequestLog.log() would be able to read
                    // x-www-form-urlencoded parameters in one case and not the other.
                    Throwable unconsumed = stream.consumeAvailable();
                    httpChannelState._consumeAvailableFailure = unconsumed;
                    if (httpChannelState.getConnectionMetaData().isPersistent() && !httpChannelState._expects100Continue)
                        failure = ExceptionUtil.combine(failure, unconsumed);
                    else if (failure != null && unconsumed != null)
                        ExceptionUtil.addSuppressedIfNotAssociated(failure, unconsumed);
                    if (LOG.isDebugEnabled())
                        LOG.debug("consumeAvailable: {} {}", unconsumed == null, httpChannelState, failure);
                }

                // Pending writes are also failures
                if (response.lockedIsWriting())
                    failure = ExceptionUtil.combine(failure, new IllegalStateException("write pending"));

                // If we are not failing (yet) and not committed, then commit and check headers
                if (failure == null && httpChannelState._responseHeaders.commit())
                {
                    responseMetaData = response.lockedPrepareResponse(httpChannelState, true);
                    // Check the response headers against the content written.
                    long totalWritten = response._contentBytesWritten;
                    long committedContentLength = httpChannelState._committedContentLength;
                    if (committedContentLength >= 0 &&
                        committedContentLength != totalWritten &&
                        !(totalWritten == 0 && (HttpMethod.HEAD.is(_request.getMethod()) || response.getStatus() == HttpStatus.NOT_MODIFIED_304)))
                        failure = ExceptionUtil.combine(failure, new IOException("content-length %d != %d written".formatted(committedContentLength, totalWritten)));
                }

                // If we are still not failing, is a last stream send needed or can we complete?
                if (failure == null)
                {
                    doLastStreamSend = httpChannelState.lockedLastStreamSend();
                    if (doLastStreamSend)
                    {
                        LastWriteCallback lastWriteCallback = httpChannelState._lastWriteCallback;
                        if (stream.isCommitted())
                        {
                            response._writeCallback = lastWriteCallback;
                        }
                        else
                        {
                            ErrorResponse errResponse = new ErrorResponse(request);
                            response._writeCallback = Callback.from(lastWriteCallback.getInvocationType(), lastWriteCallback::succeeded, x ->
                            {
                                if (stream.isCommitted())
                                    lastWriteCallback.failed(x);
                                else
                                    Response.writeError(request, errResponse, new ErrorCallback(request, errResponse, stream, x), x);
                            });
                        }
                    }
                    // or complete the stream if everything is done.
                    else if (httpChannelState.lockedIsLastStreamSendCompleted())
                    {
                        completeStream = httpChannelState._handled;
                    }
                }
                else
                {
                    // We are failing...
                    httpChannelState._callbackFailure = failure;

                    // Can we and should we generate an error response?
                    if (!stream.isCommitted() && !ExceptionUtil.hasAssociated(failure, Request.Handler.AbortException.class))
                    {
                        // We are not committed, so we can send an error response.
                        errorResponse = new ErrorResponse(request);
                    }
                    else if (httpChannelState._handled)
                    {
                        // Callback and handling are completed, so it is just a matter of the last write
                        if (httpChannelState.lockedIsLastStreamSendCompleted())
                        {
                            // We are committed, handling completed, and last write completed, so complete the stream now.
                            completeStream = true;
                        }
                        else if (response.lockedIsWriting())
                        {
                            // We are currently writing so fail the app callback now and let the write completion handle the failure
                            Runnable task = response.lockedFailWrite(failure);
                            writeCallback = Callback.from(task, httpChannelState._lastWriteCallback);
                        }
                        else
                        {
                            // There has been no last write, but we will just fail the stream instead.
                            httpChannelState._streamSendState = StreamSendState.FAILED;
                            completeStream = true;
                        }
                    }
                    else
                    {
                        // We are still handling, so for the most part let the HandlerInvoker deal with completion

                        // But if we are writing
                        if (response.lockedIsWriting())
                        {
                            // We are currently writing so fail the app callback now and let the write completion handle the failure
                            Runnable task = response.lockedFailWrite(failure);
                            writeCallback = Callback.from(task, httpChannelState._lastWriteCallback);
                        }
                        else if (!httpChannelState.lockedIsLastStreamSendCompleted())
                        {
                            // last write it is not going to happen after failure, so we can just fail anyway
                            httpChannelState._streamSendState = StreamSendState.FAILED;
                        }
                    }
                }

                completeStreamFailure = completeStream ? httpChannelState.lockedGetCompleteStreamFailure() : null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("succeeded: failure={} doLastStreamSend={} {}", failure, doLastStreamSend, this);

            if (writeCallback != null)
                writeCallback.failed(Objects.requireNonNullElseGet(failure, IOException::new));
            else if (errorResponse != null)
                Response.writeError(request, errorResponse, new ErrorCallback(request, errorResponse, stream, failure), failure);
            else if (doLastStreamSend)
                stream.send(_request._metaData, responseMetaData, true, null, response);
            else if (completeStream)
                httpChannelState.completeStream(stream, completeStreamFailure);
            else if (LOG.isDebugEnabled())
                LOG.debug("No action on succeeded {}", this);
        }

        private boolean lockedCompleteCallback()
        {
            assert _request._lock.isHeldByCurrentThread();

            HttpChannelState httpChannelState = _request._httpChannelState;
            if (httpChannelState == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("already recycled after completion {} by", _request, _completedBy);
                return true;
            }

            if (httpChannelState._callbackCompleted)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("already completed {} by", _request, _completedBy);
                    LOG.debug("Second complete", new Throwable("second complete"));
                }
                return true;
            }

            if (LOG.isDebugEnabled())
                _completedBy = new Throwable(Thread.currentThread().getName());

            httpChannelState._callbackCompleted = true;

            return false;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _request.getHttpStream().getInvocationType();
        }
    }

    /**
     * Used as the {@link Response} when writing the error response
     * from {@link ChannelCallback#failed(Throwable)}.
     */
    private static class ErrorResponse extends ChannelResponse
    {
        public ErrorResponse(ChannelRequest request)
        {
            super(request);
            _status = HttpStatus.INTERNAL_SERVER_ERROR_500;
        }

        @Override
        protected ResponseHttpFields getResponseHttpFields(HttpChannelState httpChannelState)
        {
            httpChannelState._committedContentLength = -1;
            HttpFields original = super.getResponseHttpFields(httpChannelState);
            ResponseHttpFields httpFields = new ResponseHttpFields();

            for (HttpField field : original)
            {
                HttpHeader header = field.getHeader();
                if (header == HttpHeader.SERVER || header == HttpHeader.DATE)
                    httpFields.add(field);
            }
            return httpFields;
        }

        @Override
        MetaData.Response lockedPrepareResponse(HttpChannelState httpChannelState, boolean last)
        {
            assert httpChannelState._request._lock.isHeldByCurrentThread();
            MetaData.Response httpFields = super.lockedPrepareResponse(httpChannelState, last);
            httpChannelState._response._status = _status;
            HttpFields.Mutable originalResponseFields = httpChannelState._responseHeaders.getMutableHttpFields();
            originalResponseFields.clear();
            originalResponseFields.add(getResponseHttpFields());
            return httpFields;
        }
    }

    /**
     * Used as the {@link Response} and {@link Callback} when writing the error response
     * from {@link ChannelCallback#failed(Throwable)}.
     */
    private static class ErrorCallback implements Callback
    {
        private final ChannelRequest _request;
        private final ErrorResponse _errorResponse;
        private final HttpStream _stream;
        private final Throwable _failure;

        public ErrorCallback(ChannelRequest request, ErrorResponse response, HttpStream stream, Throwable failure)
        {
            _request = request;
            _errorResponse = response;
            _stream = stream;
            _failure = failure;
        }

        /**
         * Called when the error write in {@link ChannelCallback#failed(Throwable)} succeeds.
         */
        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ErrorWrite succeeded: {}", this);
            boolean needLastWrite;
            MetaData.Response responseMetaData = null;
            HttpChannelState httpChannelState;
            Callback lastWriteCallback;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.getHttpChannelState();
                lastWriteCallback = httpChannelState._lastWriteCallback;
                // Did the ErrorHandler do the last write?
                needLastWrite = httpChannelState.lockedLastStreamSend();
                if (needLastWrite && _errorResponse.getResponseHttpFields().commit())
                    responseMetaData = _errorResponse.lockedPrepareResponse(httpChannelState, true);
            }

            if (needLastWrite)
                _stream.send(_request._metaData, responseMetaData, true, null, lastWriteCallback);
            else
                lastWriteCallback.succeeded();
        }

        /**
         * Called when the error write in {@link ChannelCallback#failed(Throwable)} fails.
         *
         * @param x The reason for the failure.
         */
        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ErrorWrite failed: {}", this, x);
            Throwable failure;
            HttpChannelState httpChannelState;
            try (AutoLock ignored = _request._lock.lock())
            {
                failure = _failure;
                httpChannelState = _request.lockedGetHttpChannelState();
                httpChannelState._response._status = _errorResponse._status;
            }
            ExceptionUtil.addSuppressedIfNotAssociated(failure, x);
            HttpChannelState.failed(httpChannelState._lastWriteCallback, failure);
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
        }
    }

    private static class InterimCallback extends Callback.Completable
    {
        private final HttpChannelState _httpChannelState;

        private InterimCallback(HttpChannelState httpChannelState)
        {
            _httpChannelState = httpChannelState;
        }

        @Override
        public void succeeded()
        {
            completing();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            try
            {
                completing();
                super.failed(x);
            }
            catch (Throwable t)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                throw t;
            }
        }

        private void completing()
        {
            try (AutoLock ignore = _httpChannelState._lock.lock())
            {
                // Allow other writes to proceed
                if (_httpChannelState._response._writeCallback == this)
                    _httpChannelState._response._writeCallback = null;
            }
        }
    }

    private class HttpChannelSerializedInvoker extends SerializedInvoker
    {
        public HttpChannelSerializedInvoker(String name, Executor executor)
        {
            super(name, executor);
        }

        @Override
        protected void onError(Runnable task, Throwable failure)
        {
            ChannelRequest request;
            boolean callbackCompleted;
            try (AutoLock ignore = _lock.lock())
            {
                callbackCompleted = _callbackCompleted;
                request = _request;
            }

            if (request == null || callbackCompleted)
            {
                // It is too late to handle error.
                super.onError(task, failure);
                return;
            }

            Runnable failureTask = onFailure(failure);
            if (failureTask != null)
                failureTask.run();
        }
    }

    /**
     * A Listener that represents multiple user {@link ComplianceViolation.Listener} instances
     */
    private static class InitializedCompositeComplianceViolationListener implements ComplianceViolation.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(InitializedCompositeComplianceViolationListener.class);
        private final List<ComplianceViolation.Listener> _listeners;

        /**
         * Construct a new ComplianceViolations that will initialize the list of listeners and notify events to all.
         *
         * @param unInitializedListeners the user listeners to initialized and notify. Null or empty list is not allowed..
         */
        public InitializedCompositeComplianceViolationListener(List<ComplianceViolation.Listener> unInitializedListeners)
        {
            List<ComplianceViolation.Listener> initialized = null;
            for (ComplianceViolation.Listener listener : unInitializedListeners)
            {
                ComplianceViolation.Listener listening = listener.initialize();
                if (listening != listener)
                {
                    initialized = new ArrayList<>(unInitializedListeners.size());
                    for (ComplianceViolation.Listener l : unInitializedListeners)
                    {
                        if (l == listener)
                            break;
                        initialized.add(l);
                    }
                }
                if (initialized != null)
                    initialized.add(listening);
            }

            _listeners = initialized == null ? unInitializedListeners : initialized;
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onRequestEnd(request);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to notify ComplianceViolation.Listener implementation at {} of onRequestEnd {}", listener, request, e);
                }
            }
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onRequestBegin(request);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to notify ComplianceViolation.Listener implementation at {} of onRequestBegin {}", listener, request, e);
                }
            }
        }

        @Override
        public ComplianceViolation.Listener initialize()
        {
            throw new IllegalStateException("already initialized");
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            assert event != null;
            for (ComplianceViolation.Listener listener : _listeners)
            {
                try
                {
                    listener.onComplianceViolation(event);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to notify ComplianceViolation.Listener implementation at {} of event {}", listener, event, e);
                }
            }
        }
    }

    /**
     * Invoke a callback failure, handling any {@link Throwable} thrown
     * by adding the passed {@code failure} as a suppressed with
     * {@link ExceptionUtil#addSuppressedIfNotAssociated(Throwable, Throwable)}.
     * @param callback The callback to fail
     * @param failure The failure
     * @throws RuntimeException If thrown, will have the {@code failure} added as a suppressed.
     */
    private static void failed(Callback callback, Throwable failure)
    {
        try
        {
            callback.failed(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
    }

    private static class ThreadPoolWrapper implements ThreadPool
    {
        private final Executor _executor;

        private ThreadPoolWrapper(Executor executor)
        {
            _executor = executor;
        }

        @Override
        public void execute(Runnable command)
        {
            _executor.execute(command);
        }

        @Override
        public void join()
        {
        }

        @Override
        public int getThreads()
        {
            return 0;
        }

        @Override
        public int getIdleThreads()
        {
            return 0;
        }

        @Override
        public boolean isLowOnThreads()
        {
            return false;
        }
    }
}
