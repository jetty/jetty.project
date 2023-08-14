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
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
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
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
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
    private static final Throwable DO_NOT_SEND = new Throwable("No Send");
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _serializedInvoker;
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
    /**
     * Failure passed to {@link #onFailure(Throwable)}
     */
    private Content.Chunk _failure;
    /**
     * Listener for {@link #onFailure(Throwable)} events
     */
    private Consumer<Throwable> _onFailure;
    /**
     * Failure passed to {@link ChannelCallback#failed(Throwable)}
     */
    private Throwable _callbackFailure;
    private Attributes _cache;

    public HttpChannelState(ConnectionMetaData connectionMetaData)
    {
        _connectionMetaData = connectionMetaData;
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _serializedInvoker = new HttpChannelSerializedInvoker();
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
            _responseHeaders.reset();
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
            _failure = null;
            _onFailure = null;
            _callbackFailure = null;
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

    // TODO: remove this
    public Connection getConnection()
    {
        return _connectionMetaData.getConnection();
    }

    // TODO: remove this
    public Connector getConnector()
    {
        return _connectionMetaData.getConnector();
    }

    // TODO: remove this
    public EndPoint getEndPoint()
    {
        return getConnection().getEndPoint();
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
        return getServer().getThreadPool();
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

            HttpFields.Mutable responseHeaders = _response.getHeaders();
            HttpConfiguration httpConfiguration = getHttpConfiguration();
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
        return _serializedInvoker.offer(onContent);
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
        Predicate<TimeoutException> onIdleTimeout;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onIdleTimeout {}", this, t);

            // if not already a failure,
            if (_failure == null)
            {
                // if we are currently demanding, take the onContentAvailable runnable to invoke below.
                Runnable invokeOnContentAvailable = _onContentAvailable;
                _onContentAvailable = null;

                // If a write call is in progress, take the writeCallback to fail below
                Runnable invokeWriteFailure = _response.lockedFailWrite(t);

                // If demand was in process, then arrange for the next read to return the idle timeout, if no other error
                // TODO to make IO timeouts transient, remove the invokeWriteFailure test below.
                //      Probably writes cannot be made transient as it will be unclear how much of the buffer has actually
                //      been written.  So write timeouts might always be persistent... but then we should call the listener
                //      before calling lockedFailedWrite above.
                if (invokeOnContentAvailable != null || invokeWriteFailure != null)
                {
                    // TODO The chunk here should be last==false, so that IO timeout is a transient failure.
                    //      However AsyncContentProducer has been written on the assumption of no transient
                    //      failures, so it needs to be updated before we can make timeouts transients.
                    //      See ServerTimeoutTest.testAsyncReadHttpIdleTimeoutOverridesIdleTimeout
                    _failure = Content.Chunk.from(t, true);
                }

                // If there was an IO operation, just deliver the idle timeout via them
                if (invokeOnContentAvailable != null || invokeWriteFailure != null)
                    return _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure);

                // Otherwise We ask any idle timeout listeners if we should call onFailure or not
                onIdleTimeout = _onIdleTimeout;
            }
            else
            {
                onIdleTimeout = null;
            }
        }

        // Ask any listener what to do
        if (onIdleTimeout != null)
        {
            Runnable onIdle = () ->
            {
                if (onIdleTimeout.test(t))
                {
                    // If the idle timeout listener(s) return true, then we call onFailure and any task it returns.
                    Runnable task = onFailure(t);
                    if (task != null)
                        task.run();
                }
            };
            return _serializedInvoker.offer(onIdle);
        }

        // otherwise treat as a failure
        return onFailure(t);
    }

    @Override
    public Runnable onFailure(Throwable x)
    {
        HttpStream stream;
        Runnable task;
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onFailure {}", this, x);

            // If the channel doesn't have a stream, then the error is ignored.
            if (_stream == null)
                return null;
            stream = _stream;

            if (_request == null)
            {
                // If the channel doesn't have a request, then the error must have occurred during the parsing of
                // the request line / headers, so make a temp request for logging and producing an error response.
                MetaData.Request errorRequest = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_1_0, HttpFields.EMPTY);
                _request = new ChannelRequest(this, errorRequest);
                _response = new ChannelResponse(_request);
            }

            // Set the error to arrange for any subsequent reads, demands or writes to fail.
            if (_failure == null)
                _failure = Content.Chunk.from(x, true);
            else if (ExceptionUtil.areNotAssociated(_failure.getFailure(), x) && _failure.getFailure().getClass() != x.getClass())
                _failure.getFailure().addSuppressed(x);

            // If not handled, then we just fail the request callback
            if (!_handled && _handling == null)
            {
                task = () -> _request._callback.failed(x);
            }
            else
            {
                // if we are currently demanding, take the onContentAvailable runnable to invoke below.
                Runnable invokeOnContentAvailable = _onContentAvailable;
                _onContentAvailable = null;

                // If a write call is in progress, take the writeCallback to fail below
                Runnable invokeWriteFailure = _response.lockedFailWrite(x);

                // Create runnable to invoke any onError listeners
                ChannelRequest request = _request;
                Runnable invokeOnFailureListeners = () ->
                {
                    Consumer<Throwable> onFailure;
                    try (AutoLock ignore = _lock.lock())
                    {
                        onFailure = _onFailure;
                    }

                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invokeListeners {} {}", HttpChannelState.this, onFailure, x);
                        if (onFailure != null)
                            onFailure.accept(x);
                    }
                    catch (Throwable throwable)
                    {
                        ExceptionUtil.addSuppressedIfNotAssociated(x, throwable);
                    }

                    // If the application has not been otherwise informed of the failure
                    if (invokeOnContentAvailable == null && invokeWriteFailure == null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("failing callback in {}", this, x);
                        request._callback.failed(x);
                    }
                };

                // Serialize all the error actions.
                task = _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnFailureListeners);
            }
        }

        // Consume content as soon as possible to open any flow control window.
        Throwable unconsumed = stream.consumeAvailable();
        if (unconsumed != null && LOG.isDebugEnabled())
            LOG.debug("consuming content during error {}", unconsumed.toString());

        return task;
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
                : DO_NOT_SEND;
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
        try (AutoLock ignored = _lock.lock())
        {
            return String.format("%s@%x{handling=%s, handled=%b, send=%s, completed=%b, request=%s}",
                this.getClass().getSimpleName(),
                hashCode(),
                _handling,
                _handled,
                _streamSendState,
                _callbackCompleted,
                _request);
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
                if (!HttpMethod.PRI.is(request.getMethod()) &&
                    !HttpMethod.CONNECT.is(request.getMethod()) &&
                    !Request.getPathInContext(_request).startsWith("/") &&
                    !HttpMethod.OPTIONS.is(request.getMethod()))
                {
                    throw new BadMessageException("Bad URI path");
                }

                HttpURI uri = request.getHttpURI();
                if (uri.hasViolations())
                {
                    String badMessage = UriCompliance.checkUriCompliance(getConnectionMetaData().getHttpConfiguration().getUriCompliance(), uri);
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

    public static class ChannelRequest implements Attributes, Request
    {
        private final long _headersNanoTime = NanoTime.now();
        private final ChannelCallback _callback = new ChannelCallback(this);
        private final String _id;
        private final ConnectionMetaData _connectionMetaData;
        private final MetaData.Request _metaData;
        private final AutoLock _lock;
        private final LongAdder _contentBytesRead = new LongAdder();
        private final Attributes _attributes = new Attributes.Lazy();
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
        public Object getAttribute(String name)
        {
            if (name.startsWith("org.eclipse.jetty"))
            {
                if (Server.class.getName().equals(name))
                    return getConnectionMetaData().getConnector().getServer();
                if (HttpChannelState.class.getName().equals(name))
                    return getHttpChannelState();
                // TODO: is the instanceof needed?
                // TODO: possibly remove this if statement or move to Servlet.
                if (HttpConnection.class.getName().equals(name) &&
                    getConnectionMetaData().getConnection() instanceof HttpConnection)
                    return getConnectionMetaData().getConnection();
            }
            return _attributes.getAttribute(name);
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _attributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (Server.class.getName().equals(name) || HttpChannelState.class.getName().equals(name) || HttpConnection.class.getName().equals(name))
                return null;
            return _attributes.setAttribute(name, attribute);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _attributes.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            _attributes.clearAttributes();
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
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();

                Content.Chunk error = httpChannel._failure;
                httpChannel._failure = Content.Chunk.next(error);
                if (error != null)
                    return error;

                stream = httpChannel._stream;
            }

            Content.Chunk chunk = stream.read();

            if (LOG.isDebugEnabled())
                LOG.debug("read {}", chunk);

            if (chunk != null && chunk.hasRemaining())
                _contentBytesRead.add(chunk.getByteBuffer().remaining());

            if (chunk instanceof Trailers trailers)
                _trailers = trailers.getTrailers();

            return chunk;
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
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();

                if (LOG.isDebugEnabled())
                    LOG.debug("demand {}", httpChannel);

                error = httpChannel._failure != null;
                if (!error)
                {
                    if (httpChannel._onContentAvailable != null)
                        throw new IllegalArgumentException("demand pending");
                    httpChannel._onContentAvailable = demandCallback;
                }

                stream = httpChannel._stream;
            }

            if (error)
                // TODO: can we avoid re-grabbing the lock to get the HttpChannel?
                getHttpChannelState()._serializedInvoker.run(demandCallback);
            else
                stream.demand();
        }

        @Override
        public void fail(Throwable failure)
        {
            Runnable runnable = _httpChannelState.onFailure(failure);
            if (runnable != null)
                getContext().execute(runnable);
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

                if (httpChannel._failure != null)
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

                if (httpChannel._failure != null)
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
            _writeCallback = null;
            return writeCallback == null ? null : () -> writeCallback.failed(x);
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
            HttpStream stream = null;
            Throwable failure;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.lockedGetHttpChannelState();
                long committedContentLength = httpChannelState._committedContentLength;
                long totalWritten = _contentBytesWritten + length;
                long contentLength = committedContentLength >= 0 ? committedContentLength : getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);

                if (_writeCallback != null)
                    failure = new IllegalStateException("write pending");
                else
                {
                    failure = getFailure(httpChannelState);
                    if (failure == null && contentLength >= 0 && totalWritten != contentLength)
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
                            failure = new IOException(message);
                        }
                    }
                }

                // If no failure by this point, we can try to send
                if (failure == null)
                    failure = httpChannelState.lockedStreamSend(last, length);

                // Have we failed in some way?
                if (failure == DO_NOT_SEND)
                {
                    httpChannelState._serializedInvoker.run(callback::succeeded);
                }
                else if (failure != null)
                {
                    Throwable throwable = failure;
                    httpChannelState._serializedInvoker.run(() -> callback.failed(throwable));
                }
                else
                {
                    // We have not failed, so we will do a stream send
                    _writeCallback = callback;
                    _contentBytesWritten = totalWritten;
                    stream = httpChannelState._stream;
                    if (_httpFields.commit())
                        responseMetaData = lockedPrepareResponse(httpChannelState, last);
                }
            }

            if (failure == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("writing last={} {} {}", last, BufferUtil.toDetailString(content), this);
                stream.send(_request._metaData, responseMetaData, last, content, this);
            }
        }

        protected Throwable getFailure(HttpChannelState httpChannelState)
        {
            Content.Chunk failure = httpChannelState._failure;
            return failure == null ? null : failure.getFailure();
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
            // Called when an individual write succeeds.
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannelState();
                callback = _writeCallback;
                _writeCallback = null;
                httpChannel.lockedStreamSendCompleted(true);
            }
            if (callback != null)
                httpChannel._serializedInvoker.run(callback::succeeded);
        }

        /**
         * Called when the call to
         * {@link HttpStream#send(MetaData.Request, MetaData.Response, boolean, ByteBuffer, Callback)}
         * made by {@link ChannelResponse#write(boolean, ByteBuffer, Callback)} fails.
         * <p>
         * The implementation maintains the {@link #_streamSendState} before taking
         * and serializing the call to the {@link #_writeCallback}, which was set by the call to {@code write}.
         * @param x The reason for the failure.
         */
        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("write failed {}", this, x);
            // Called when an individual write succeeds.
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannelState();
                callback = _writeCallback;
                _writeCallback = null;
                httpChannel.lockedStreamSendCompleted(false);
            }
            if (callback != null)
                httpChannel._serializedInvoker.run(() -> callback.failed(x));
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
            Completable completable = new Completable();
            if (HttpStatus.isInterim(status))
            {
                HttpChannelState channel = _request.getHttpChannelState();
                HttpVersion version = channel.getConnectionMetaData().getHttpVersion();
                MetaData.Response response = new MetaData.Response(status, null, version, headers);
                channel._stream.send(_request._metaData, response, false, null, completable);
            }
            else
            {
                completable.failed(new IllegalArgumentException("Invalid interim status code: " + status));
            }
            return completable;
        }

        MetaData.Response lockedPrepareResponse(HttpChannelState httpChannel, boolean last)
        {
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
                request = _request;
                httpChannelState = _request._httpChannelState;
                response = httpChannelState._response;
                stream = httpChannelState._stream;

                // We are being tough on handler implementations and expect them
                // to not have pending operations when calling succeeded or failed.
                if (httpChannelState._onContentAvailable != null)
                    throw new IllegalStateException("demand pending");
                if (response.lockedIsWriting())
                    throw new IllegalStateException("write pending");

                if (lockedCompleteCallback())
                    return;

                assert httpChannelState._callbackFailure == null;

                needLastStreamSend = httpChannelState.lockedLastStreamSend();
                completeStream = !needLastStreamSend && httpChannelState._handling == null && httpChannelState.lockedIsLastStreamSendCompleted();

                if (httpChannelState._responseHeaders.commit())
                    responseMetaData = response.lockedPrepareResponse(httpChannelState, true);

                long totalWritten = response._contentBytesWritten;
                long committedContentLength = httpChannelState._committedContentLength;

                if (committedContentLength >= 0 && committedContentLength != totalWritten && !(totalWritten == 0 && HttpMethod.HEAD.is(_request.getMethod())))
                    failure = new IOException("content-length %d != %d written".formatted(committedContentLength, totalWritten));

                // is the request fully consumed?
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
                stream.send(_request._metaData, responseMetaData, true, null, httpChannelState._handlerInvoker);
            else if (completeStream)
                httpChannelState._handlerInvoker.completeStream(stream, failure);
            else if (LOG.isDebugEnabled())
                LOG.debug("No action on succeeded {}", this);
        }

        /**
         * Called when the {@link Handler} (or it's delegates) fail the request handling.
         * @param failure The reason for the failure.
         */
        @Override
        public void failed(Throwable failure)
        {
            // Called when the request/response cycle is completing with a failure.
            HttpStream stream;
            ChannelRequest request;
            HttpChannelState httpChannelState;
            ErrorResponse errorResponse = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request._httpChannelState;
                stream = httpChannelState._stream;
                request = _request;

                if (lockedCompleteCallback())
                    return;
                assert httpChannelState._callbackFailure == null;

                httpChannelState._callbackFailure = failure;

                // Consume any input.
                Throwable unconsumed = stream.consumeAvailable();
                ExceptionUtil.addSuppressedIfNotAssociated(failure, unconsumed);

                if (LOG.isDebugEnabled())
                    LOG.debug("failed stream.isCommitted={}, response.isCommitted={} {}", httpChannelState._stream.isCommitted(), httpChannelState._response.isCommitted(), this);

                if (!stream.isCommitted())
                    errorResponse = new ErrorResponse(request);
            }

            if (errorResponse != null)
                Response.writeError(request, errorResponse, new ErrorCallback(request, errorResponse, stream, failure), failure);
            else
                _request.getHttpChannelState()._handlerInvoker.failed(failure);
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
            // TODO review this as it is probably not correct
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
        protected Throwable getFailure(HttpChannelState httpChannelState)
        {
            // we ignore channel failures so we can try to generate an error response.
            return null;
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
                httpChannelState._handlerInvoker.failed(failure);
            }
        }

        /**
         * Called when the error write in {@link HttpChannelState.ChannelCallback#failed(Throwable)} fails.
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
            if (ExceptionUtil.areNotAssociated(failure, x))
                failure.addSuppressed(x);
            httpChannelState._handlerInvoker.failed(failure);
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(getClass().getSimpleName(), hashCode());
        }
    }

    private class HttpChannelSerializedInvoker extends SerializedInvoker
    {
        @Override
        protected void onError(Runnable task, Throwable failure)
        {
            ChannelRequest request;
            Content.Chunk error;
            boolean callbackCompleted;
            try (AutoLock ignore = _lock.lock())
            {
                callbackCompleted = _callbackCompleted;
                request = _request;
                error = _request == null ? null : _failure;
            }

            if (request == null || callbackCompleted)
            {
                // It is too late to handle error, so just log it
                super.onError(task, failure);
            }
            else if (error == null)
            {
                // Try to fail the request, but we might lose a race.
                try
                {
                    request._callback.failed(failure);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(failure, t);
                    super.onError(task, failure);
                }
            }
            else
            {
                // We are already in error, so we will not handle this one,
                // but we will add as suppressed if we have not seen it already.
                Throwable cause = error.getFailure();
                if (ExceptionUtil.areNotAssociated(cause, failure))
                    error.getFailure().addSuppressed(failure);
            }
        }
    }
}
