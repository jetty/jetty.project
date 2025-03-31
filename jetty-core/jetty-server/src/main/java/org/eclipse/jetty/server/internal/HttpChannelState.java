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
import java.util.HashMap;
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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MultiPartFormData.Parts;
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
        LAST_COMPLETE
    }

    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelState.class);
    private static final Throwable NOTHING_TO_SEND = new Throwable("nothing_to_send");
    private static final HttpField SERVER_VERSION = new ResponseHttpFields.PersistentPreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new ResponseHttpFields.PersistentPreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
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
        List<ComplianceViolation.Listener> listeners = _connectionMetaData.getHttpConfiguration().getComplianceViolationListeners();
        _complianceViolationListener = switch (listeners.size())
        {
            case 0 -> ComplianceViolation.Listener.NOOP;
            case 1 -> listeners.get(0).initialize();
            default -> new InitializedCompositeComplianceViolationListener(listeners);
        };
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
        Executor executor = getServer().getThreadPool();
        Executor virtualExecutor = VirtualThreads.getVirtualThreadsExecutor(executor);
        return virtualExecutor != null ? virtualExecutor : executor;
    }

    @Override
    public Attributes getCache()
    {
        if (_cache == null)
        {
            if (getConnectionMetaData().isPersistent())
                _cache = new Attributes.Mapped(new HashMap<>());
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

    public boolean isRequestHandled()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _handling != null || _handled;
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
        return _readInvoker.offer(onContent);
    }

    @Override
    public Invocable.InvocationType getInvocationType()
    {
        // TODO Can this actually be done, as we may need to invoke other Runnables after onContent?
        //      Could we at least avoid the lock???
        Runnable onContent;
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return null;
            onContent = _onContentAvailable;
        }
        return Invocable.getInvocationType(onContent);
    }

    @Override
    public Runnable onIdleTimeout(TimeoutException t)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onIdleTimeout {}", this, t);

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
                return Invocable.combine(_readInvoker.offer(invokeOnContentAvailable), _writeInvoker.offer(invokeWriteFailure));

            // Otherwise, if there are idle timeout listeners, ask them whether we should call onFailure.
            Predicate<TimeoutException> onIdleTimeout = _onIdleTimeout;
            if (onIdleTimeout != null)
            {
                return () ->
                {
                    if (onIdleTimeout.test(t))
                    {
                        // If the idle timeout listener(s) return true, then we call onFailure and run any task it returns.
                        Runnable task = onFailure(t);
                        if (task != null)
                            task.run();
                    }
                };
            }
        }

        // Otherwise treat as a failure.
        return onFailure(t);
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
                LOG.debug("onFailure {}", this, x);

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

                boolean skipListeners = remote && !getHttpConfiguration().isNotifyRemoteAsyncErrors();
                Runnable invokeOnFailureListeners = onFailure == null || skipListeners ? null : () ->
                {
                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invokeListeners {} {}", HttpChannelState.this, onFailure, x);
                        onFailure.accept(x);
                    }
                    catch (Throwable throwable)
                    {
                        ExceptionUtil.addSuppressedIfNotAssociated(x, throwable);
                    }
                };

                // Serialize all the error actions.
                task = Invocable.combine(_readInvoker.offer(invokeOnContentAvailable), _writeInvoker.offer(invokeWriteFailure), _readInvoker.offer(invokeOnFailureListeners));
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
            if (_stream == null)
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
        return _streamSendState == StreamSendState.LAST_COMPLETE;
    }

    private boolean lockedLastStreamSend()
    {
        assert _lock.isHeldByCurrentThread();
        if (_streamSendState != StreamSendState.SENDING)
            return false;

        _streamSendState = StreamSendState.LAST_SENDING;
        return true;
    }

    @Override
    public String toString()
    {
        try (AutoLock lock = _lock.tryLock())
        {
            boolean held = lock.isHeldByCurrentThread();
            return String.format("%s@%x{handling=%s, handled=%s, send=%s, completed=%s, request=%s}",
                this.getClass().getSimpleName(),
                hashCode(),
                held ? _handling : "?",
                held ? _handled : "?",
                held ? _streamSendState : "?",
                held ? _callbackCompleted : "?",
                held ? _request : "?"
            );
        }
    }

    private class HandlerInvoker implements Invocable.Task, Callback
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
                String pathInContext = Request.getPathInContext(request);
                if (pathInContext != null && !pathInContext.startsWith("/"))
                {
                    String method = request.getMethod();
                    if (!HttpMethod.PRI.is(method) && !HttpMethod.CONNECT.is(method) && !HttpMethod.OPTIONS.is(method))
                        throw new BadMessageException("Bad URI path");
                }

                HttpURI uri = request.getHttpURI();
                if (uri.hasViolations())
                {
                    String badMessage = UriCompliance.checkUriCompliance(getConnectionMetaData().getHttpConfiguration().getUriCompliance(), uri, HttpChannel.from(request).getComplianceViolationListener());
                    if (badMessage != null)
                        throw new BadMessageException(badMessage);
                }

                // Customize before processing.
                HttpConfiguration configuration = getHttpConfiguration();

                Request customized = request;
                HttpFields.Mutable responseHeaders = response.getHeaders();
                for (HttpConfiguration.Customizer customizer : configuration.getCustomizers())
                {
                    Request next = customizer.customize(customized, responseHeaders);
                    customized = next == null ? customized : next;
                }

                if (customized != request && server.getRequestLog() != null)
                    request.setLoggedRequest(customized);

                if (!server.handle(customized, response, request._callback))
                    Response.writeError(customized, response, request._callback, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                request._callback.failed(t);
            }

            HttpStream stream;
            Throwable failure;
            boolean completeStream;
            boolean callbackCompleted;
            boolean lastStreamSendComplete;

            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                _handling = null;
                _handled = true;
                failure = _callbackFailure;
                callbackCompleted = _callbackCompleted;
                lastStreamSendComplete = lockedIsLastStreamSendCompleted();
                completeStream = callbackCompleted && lastStreamSendComplete;

                if (LOG.isDebugEnabled())
                    LOG.debug("handler invoked: completeStream={} failure={} callbackCompleted={} {}", completeStream, failure, callbackCompleted, HttpChannelState.this);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("stream={}, failure={}, callbackCompleted={}, completeStream={}", stream, failure, callbackCompleted, completeStream);

            if (completeStream)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("completeStream({}, {})", stream, Objects.toString(failure));
                completeStream(stream, failure);
            }
        }

        /**
         * Called only as {@link Callback} by last write from {@link ChannelCallback#succeeded}
         */
        @Override
        public void succeeded()
        {
            HttpStream stream;
            boolean completeStream;
            try (AutoLock ignored = _lock.lock())
            {
                assert _callbackCompleted;
                _streamSendState = StreamSendState.LAST_COMPLETE;
                completeStream = _handling == null;
                stream = _stream;
            }

            if (completeStream)
                completeStream(stream, null);
        }

        /**
         * Called only as {@link Callback} by last send from {@link ChannelCallback#succeeded}
         */
        @Override
        public void failed(Throwable failure)
        {
            HttpStream stream;
            boolean completeStream;
            try (AutoLock ignored = _lock.lock())
            {
                assert _callbackCompleted;
                _streamSendState = StreamSendState.LAST_COMPLETE;
                completeStream = _handling == null;
                stream = _stream;
                failure = _callbackFailure = ExceptionUtil.combine(_callbackFailure, failure);
            }
            if (completeStream)
                completeStream(stream, failure);
        }

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
                Parts parts = (Parts)_request.getAttribute(Parts.class.getName());
                if (parts != null)
                    parts.close();

                long idleTO = getHttpConfiguration().getIdleTimeout();
                if (idleTO > 0 && _oldIdleTimeout != idleTO)
                    stream.setIdleTimeout(_oldIdleTimeout);
            }
            finally
            {
                ComplianceViolation.Listener listener = getComplianceViolationListener();
                if (listener != null)
                    listener.onRequestEnd(_request);

                // This is THE ONLY PLACE the stream is succeeded or failed.
                if (failure == null)
                    stream.succeeded();
                else
                    stream.failed(failure);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getConnectionMetaData().getConnector().getServer().getInvocationType();
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
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();
                stream = httpChannel._stream;
            }

            return stream.consumeAvailable() == null;
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
        private Throwable _writeFailure;

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
            _writeCallback = null;
            if (writeCallback == null)
                return null;
            _writeFailure = ExceptionUtil.combine(_writeFailure, x);
            return () -> HttpChannelState.failed(writeCallback, x);
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
            long length = BufferUtil.length(content);

            HttpChannelState httpChannelState;
            HttpStream stream;
            Throwable writeFailure;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.lockedGetHttpChannelState();
                long totalWritten = _contentBytesWritten + length;
                writeFailure = _writeFailure;

                if (writeFailure == null)
                {
                    if (_writeCallback != null)
                    {
                        if (_writeCallback instanceof InterimCallback interimCallback)
                        {
                            // Do this write after the interim callback.
                            interimCallback.whenComplete((v, t) -> write(last, content, callback));
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
                                    LOG.debug("fail {} {}", callback, message);
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
                    httpChannelState._writeInvoker.run(callback::succeeded);
                    return;
                }
                // Have we failed in some way?
                if (writeFailure != null)
                {
                    Throwable failure = writeFailure;
                    httpChannelState._writeInvoker.run(() -> HttpChannelState.failed(callback, failure));
                    return;
                }

                // No failure, do the actual stream send using the ChannelResponse as the callback.
                _writeCallback = callback;
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
                httpChannel._writeInvoker.run(callback::succeeded);
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
                _writeFailure = x;
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
            return "%s@%x{%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), getStatus(), getRequest());
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
            // Called when the request/response cycle is completing successfully.
            HttpStream stream;
            boolean needLastStreamSend;
            HttpChannelState httpChannelState;
            Throwable failure = null;
            ChannelRequest request;
            ChannelResponse response;
            MetaData.Response responseMetaData = null;
            boolean completeStream;
            ErrorResponse errorResponse = null;

            try (AutoLock ignored = _request._lock.lock())
            {
                if (lockedCompleteCallback())
                    return;

                request = _request;
                httpChannelState = _request._httpChannelState;
                response = httpChannelState._response;
                stream = httpChannelState._stream;

                // We convert a call to succeeded with pending demand/write into a call to failed.
                if (httpChannelState._onContentAvailable != null)
                    failure = ExceptionUtil.combine(failure, new IllegalStateException("demand pending"));
                if (response.lockedIsWriting())
                    failure = ExceptionUtil.combine(failure, new IllegalStateException("write pending"));

                assert httpChannelState._callbackFailure == null;

                needLastStreamSend = httpChannelState.lockedLastStreamSend();
                completeStream = !needLastStreamSend && httpChannelState._handling == null && httpChannelState.lockedIsLastStreamSendCompleted();
                if (needLastStreamSend)
                    response._writeCallback = httpChannelState._handlerInvoker;

                if (httpChannelState._responseHeaders.commit())
                    responseMetaData = response.lockedPrepareResponse(httpChannelState, true);

                long totalWritten = response._contentBytesWritten;
                long committedContentLength = httpChannelState._committedContentLength;

                if (committedContentLength >= 0 &&
                    committedContentLength != totalWritten &&
                    !(totalWritten == 0 && (HttpMethod.HEAD.is(_request.getMethod()) || response.getStatus() == HttpStatus.NOT_MODIFIED_304)))
                    failure = ExceptionUtil.combine(failure, new IOException("content-length %d != %d written".formatted(committedContentLength, totalWritten)));

                // Is the request fully consumed?
                Throwable unconsumed = stream.consumeAvailable();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAvailable: {} {} ", unconsumed == null, httpChannelState);

                if (unconsumed != null && httpChannelState.getConnectionMetaData().isPersistent())
                    failure = ExceptionUtil.combine(failure, unconsumed);

                if (failure != null)
                {
                    httpChannelState._callbackFailure = failure;
                    if (!stream.isCommitted())
                        errorResponse = new ErrorResponse(request);
                    else
                        completeStream = true;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("succeeded: failure={} needLastStreamSend={} {}", failure, needLastStreamSend, this);

            if (errorResponse != null)
                Response.writeError(request, errorResponse, new ErrorCallback(request, errorResponse, stream, failure), failure);
            else if (needLastStreamSend)
                stream.send(_request._metaData, responseMetaData, true, null, response);
            else if (completeStream)
                httpChannelState._handlerInvoker.completeStream(stream, failure);
            else if (LOG.isDebugEnabled())
                LOG.debug("No action on succeeded {}", this);
        }

        /**
         * Called when the {@link Handler} (or it's delegates) fail the request handling.
         *
         * @param failure The reason for the failure.
         */
        @Override
        public void failed(Throwable failure)
        {
            try
            {
                // Called when the request/response cycle is completing with a failure.
                HttpStream stream;
                ChannelRequest request;
                HttpChannelState httpChannelState;
                ErrorResponse errorResponse = null;
                try (AutoLock ignored = _request._lock.lock())
                {
                    if (lockedCompleteCallback())
                        return;
                    httpChannelState = _request._httpChannelState;
                    stream = httpChannelState._stream;
                    request = _request;

                    assert httpChannelState._callbackFailure == null;

                    httpChannelState._callbackFailure = failure;

                    if (!stream.isCommitted() && !(failure instanceof Request.Handler.AbortException))
                    {
                        // Consume any input.
                        Throwable unconsumed = stream.consumeAvailable();
                        ExceptionUtil.addSuppressedIfNotAssociated(failure, unconsumed);

                        ChannelResponse response = httpChannelState._response;
                        if (LOG.isDebugEnabled())
                            LOG.debug("failed stream.isCommitted={}, response.isCommitted={} {}", stream.isCommitted(), response.isCommitted(), this);

                        errorResponse = new ErrorResponse(request);
                    }
                }

                if (errorResponse != null)
                    Response.writeError(request, errorResponse, new ErrorCallback(request, errorResponse, stream, failure), failure);
                else
                    _request.getHttpChannelState()._handlerInvoker.failed(failure);
            }
            catch (Throwable t)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
                throw t;
            }
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
     * from {@link HttpChannelState.ChannelCallback#failed(Throwable)}.
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
     * from {@link HttpChannelState.ChannelCallback#failed(Throwable)}.
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
         * Called when the error write in {@link HttpChannelState.ChannelCallback#failed(Throwable)} succeeds.
         */
        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ErrorWrite succeeded: {}", this);
            boolean needLastWrite;
            MetaData.Response responseMetaData = null;
            HttpChannelState httpChannelState;
            Throwable failure;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.getHttpChannelState();
                failure = _failure;

                // Did the ErrorHandler do the last write?
                needLastWrite = httpChannelState.lockedLastStreamSend();
                if (needLastWrite && _errorResponse.getResponseHttpFields().commit())
                    responseMetaData = _errorResponse.lockedPrepareResponse(httpChannelState, true);
            }

            if (needLastWrite)
            {
                _stream.send(_request._metaData, responseMetaData, true, null,
                    Callback.from(() -> httpChannelState._handlerInvoker.failed(failure),
                        x ->
                        {
                            ExceptionUtil.addSuppressedIfNotAssociated(failure, x);
                            httpChannelState._handlerInvoker.failed(failure);
                        }));
            }
            else
            {
                HttpChannelState.failed(httpChannelState._handlerInvoker, failure);
            }
        }

        /**
         * Called when the error write in {@link HttpChannelState.ChannelCallback#failed(Throwable)} fails.
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
            HttpChannelState.failed(httpChannelState._handlerInvoker, failure);
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(getClass().getSimpleName(), hashCode());
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
