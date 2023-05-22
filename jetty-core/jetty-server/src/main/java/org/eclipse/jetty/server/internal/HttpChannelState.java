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
import java.util.concurrent.atomic.LongAdder;
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
import org.eclipse.jetty.server.AbstractConnector;
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
    private static final MetaData.Request ERROR_REQUEST = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_1_0, HttpFields.EMPTY);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _serializedInvoker;
    private final Attributes _requestAttributes = new Attributes.Lazy();
    private final ResponseHttpFields _responseHeaders = new ResponseHttpFields();
    private final HttpChannel.Listener _combinedListener;
    private Thread _handling;
    private boolean _handled;
    private StreamSendState _streamSendState = StreamSendState.SENDING;
    private boolean _callbackCompleted = false;
    private ChannelRequest _request;
    private ChannelResponse _response;
    private HttpStream _stream;
    private long _committedContentLength = -1;
    private Runnable _onContentAvailable;
    private Content.Chunk.Error _error;
    private Throwable _failure;
    private Predicate<Throwable> _onError;
    private Attributes _cache;

    public HttpChannelState(ConnectionMetaData connectionMetaData)
    {
        _connectionMetaData = connectionMetaData;
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _serializedInvoker = new HttpChannelSerializedInvoker();

        Connector connector = connectionMetaData.getConnector();
        _combinedListener = (connector instanceof AbstractConnector)
            ? ((AbstractConnector)connector).getHttpChannelListeners()
            : AbstractConnector.NOOP_LISTENER;
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

            // Break the links with the upper and lower layers.
            _request = null;
            _response = null;
            _stream = null;
            _streamSendState = StreamSendState.SENDING;

            // Recycle.
            _requestAttributes.clearAttributes();
            _responseHeaders.reset();
            _handling = null;
            _handled = false;
            _callbackCompleted = false;
            _failure = null;
            _committedContentLength = -1;
            _onContentAvailable = null;
            _error = null;
            _onError = null;
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
            _response = new ChannelResponse(this, _request);

            HttpFields.Mutable responseHeaders = _response.getHeaders();
            if (getHttpConfiguration().getSendServerVersion())
                responseHeaders.add(SERVER_VERSION);
            if (getHttpConfiguration().getSendXPoweredBy())
                responseHeaders.add(POWERED_BY);
            if (getHttpConfiguration().getSendDateHeader())
                responseHeaders.add(getConnectionMetaData().getConnector().getServer().getDateField());

            _combinedListener.onRequestBegin(_request);

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

    public Runnable onFailure(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFailure {}", this, x);

        HttpStream stream;
        Runnable task;
        try (AutoLock ignored = _lock.lock())
        {
            // If the channel doesn't have a stream, then the error is ignored.
            if (_stream == null)
                return null;
            stream = _stream;

            if (_request == null)
            {
                // If the channel doesn't have a request, then the error must have occurred during the parsing of
                // the request line / headers, so make a temp request for logging and producing an error response.
                _request = new ChannelRequest(this, ERROR_REQUEST);
                _response = new ChannelResponse(this, _request);
            }

            // Set the error to arrange for any subsequent reads, demands or writes to fail.
            if (_error == null)
            {
                _error = Content.Chunk.from(x);
            }
            else if (_error.getCause() != x)
            {
                _error.getCause().addSuppressed(x);
                return null;
            }

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
                Runnable invokeListeners = () ->
                {
                    Predicate<Throwable> onError;
                    try (AutoLock ignore = _lock.lock())
                    {
                        onError = _onError;
                    }

                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("invokeListeners {} {}", HttpChannelState.this, onError, x);
                        if (onError.test(x))
                            return;
                    }
                    catch (Throwable throwable)
                    {
                        if (ExceptionUtil.areNotAssociated(x, throwable))
                            x.addSuppressed(throwable);
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
                task = _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure, invokeListeners);
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

            boolean handled = false;
            Throwable failure = null;
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

                _combinedListener.onBeforeHandling(request);

                handled = server.handle(customized, response, request._callback);
                if (!handled)
                    Response.writeError(customized, response, request._callback, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                request._callback.failed(t);
                failure = t;
            }

            _combinedListener.onAfterHandling(request, handled, failure);

            HttpStream stream;
            boolean completeStream;
            boolean callbackCompleted;
            boolean lastStreamSendComplete;

            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                _handling = null;
                _handled = true;
                failure = _failure;
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
                failure = _failure = ExceptionUtil.combine(_failure, failure);
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
            }
            finally
            {
                _combinedListener.onComplete(_request, failure);
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
        private final long _timeStamp = System.currentTimeMillis();
        private final ChannelCallback _callback = new ChannelCallback(this);
        private final String _id;
        private final ConnectionMetaData _connectionMetaData;
        private final MetaData.Request _metaData;
        private final AutoLock _lock;
        private final LongAdder _contentBytesRead = new LongAdder();
        private final HttpChannel.Listener _listener;
        private HttpChannelState _httpChannelState;
        private Request _loggedRequest;
        private HttpFields _trailers;

        ChannelRequest(HttpChannelState httpChannelState, MetaData.Request metaData)
        {
            _httpChannelState = Objects.requireNonNull(httpChannelState);
            _id = httpChannelState.getHttpStream().getId(); // Copy ID now, as stream will ultimately be nulled
            _connectionMetaData = httpChannelState.getConnectionMetaData();
            _metaData = Objects.requireNonNull(metaData);
            _listener = httpChannelState._combinedListener;
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
            HttpChannelState httpChannel = getHttpChannelState();
            if (name.startsWith("org.eclipse.jetty"))
            {
                if (Server.class.getName().equals(name))
                    return httpChannel.getConnectionMetaData().getConnector().getServer();
                if (HttpChannelState.class.getName().equals(name))
                    return httpChannel;
                // TODO: is the instanceof needed?
                // TODO: possibly remove this if statement or move to Servlet.
                if (HttpConnection.class.getName().equals(name) &&
                    getConnectionMetaData().getConnection() instanceof HttpConnection)
                    return getConnectionMetaData().getConnection();
            }
            return httpChannel._requestAttributes.getAttribute(name);
        }

        @Override
        public Object removeAttribute(String name)
        {
            return getHttpChannelState()._requestAttributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (Server.class.getName().equals(name) || HttpChannelState.class.getName().equals(name) || HttpConnection.class.getName().equals(name))
                return null;
            return getHttpChannelState()._requestAttributes.setAttribute(name, attribute);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return getHttpChannelState()._requestAttributes.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            getHttpChannelState()._requestAttributes.clearAttributes();
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

        HttpChannelState getHttpChannelState()
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
        public long getTimeStamp()
        {
            return _timeStamp;
        }

        @Override
        public long getNanoTime()
        {
            HttpStream stream = _httpChannelState.getHttpStream();
            if (stream != null)
                return stream.getNanoTime();
            throw new IllegalStateException();
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

                Content.Chunk error = httpChannel._error;
                if (error != null)
                    return error;

                stream = httpChannel._stream;
            }

            Content.Chunk chunk = stream.read();

            if (LOG.isDebugEnabled())
                LOG.debug("read {}", chunk);

            if (chunk != null)
            {
                _listener.onRequestRead(this, chunk);
                if (chunk.hasRemaining())
                    _contentBytesRead.add(chunk.getByteBuffer().remaining());
            }

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

                error = httpChannel._error != null;
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
            _httpChannelState.onFailure(failure);
        }

        @Override
        public void push(MetaData.Request resource)
        {
            getHttpStream().push(resource);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannelState();

                if (httpChannel._error != null)
                    return false;

                if (httpChannel._onError == null)
                {
                    httpChannel._onError = onError;
                }
                else
                {
                    Predicate<Throwable> previous = httpChannel._onError;
                    httpChannel._onError = throwable ->
                    {
                        if (!previous.test(throwable))
                            return onError.test(throwable);
                        return true;
                    };
                }
                return true;
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
        private int _status;
        private long _contentBytesWritten;
        private Supplier<HttpFields> _trailers;
        private HttpChannel.Listener _listener;
        private Callback _writeCallback;
        protected boolean _errorMode;

        private ChannelResponse(HttpChannelState httpChannelState, ChannelRequest request)
        {
            _request = request;
            _listener = httpChannelState._combinedListener;
        }

        private void lockedPrepareErrorResponse()
        {
            // reset the response state, so we can generate an error response,
            // remembering any server or date headers (probably a nicer way of doing this).
            HttpChannelState httpChannelState = _request.lockedGetHttpChannelState();
            HttpField serverField = httpChannelState._responseHeaders.getField(HttpHeader.SERVER);
            HttpField dateField = httpChannelState._responseHeaders.getField(HttpHeader.DATE);
            httpChannelState._responseHeaders.reset();
            httpChannelState._committedContentLength = -1;
            reset();
            if (serverField != null)
                httpChannelState._responseHeaders.put(serverField);
            if (dateField != null)
                httpChannelState._responseHeaders.put(dateField);
            setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            _errorMode = true;
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
            return _request.getHttpChannelState()._responseHeaders;
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
            boolean isCommitted = isCommitted();
            long length = BufferUtil.length(content);
            long totalWritten;
            HttpChannelState httpChannelState;
            HttpStream stream = null;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request.lockedGetHttpChannelState();
                long committedContentLength = httpChannelState._committedContentLength;
                totalWritten = _contentBytesWritten + length;
                long contentLength = committedContentLength >= 0 ? committedContentLength : getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);

                if (_writeCallback != null)
                    failure = new IllegalStateException("write pending");
                else if (!_errorMode && httpChannelState._error != null)
                    failure = httpChannelState._error.getCause();
                else if (contentLength >= 0)
                {
                    // If the content length were not compatible with what was written, then we need to abort.
                    String lengthError = (totalWritten > contentLength) ? "written %d > %d content-length"
                        : (last && totalWritten < contentLength) ? "written %d < %d content-length" : null;
                    if (lengthError != null)
                    {
                        String message = lengthError.formatted(totalWritten, contentLength);
                        if (LOG.isDebugEnabled())
                            LOG.debug("fail {} {}", callback, message);
                        failure = new IOException(message);
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
                    httpChannelState._serializedInvoker.run(() ->
                    {
                        _listener.onResponseWrite(_request, last, content.slice(), throwable);
                        callback.failed(throwable);
                    });
                }
                else
                {
                    // We have not failed, so we will do a stream send
                    _writeCallback = callback;
                    _contentBytesWritten = totalWritten;
                    stream = httpChannelState._stream;
                    if (httpChannelState._responseHeaders.commit())
                        responseMetaData = lockedPrepareResponse(httpChannelState, last);
                }
            }

            if (!isCommitted)
                _listener.onResponseCommitted(_request, this._status, this.getHeaders());

            if (failure == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("writing last={} {} {}", last, BufferUtil.toDetailString(content), this);
                ByteBuffer contentSlice = content.slice();
                Callback listenerCallback = Callback.from(() ->
                {
                    _listener.onResponseWrite(_request, last, contentSlice, null);
                }, this);
                stream.send(_request._metaData, responseMetaData, last, content, listenerCallback);
            }
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
            return _request.getHttpChannelState()._responseHeaders.isCommitted();
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                if (_request._httpChannelState == null)
                    return false;

                return _request._httpChannelState._callbackCompleted && _request._httpChannelState._failure == null;
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
            HttpFields.Mutable mutableHeaders = httpChannel._responseHeaders.getMutableHttpFields();
            httpChannel._committedContentLength = mutableHeaders.getLongField(HttpHeader.CONTENT_LENGTH);
            if (last && httpChannel._committedContentLength < 0L)
            {
                httpChannel._committedContentLength = _contentBytesWritten;
                mutableHeaders.put(HttpHeader.CONTENT_LENGTH, httpChannel._committedContentLength);
            }

            httpChannel._stream.prepareResponse(mutableHeaders);

            return new MetaData.Response(
                _status, null, httpChannel.getConnectionMetaData().getHttpVersion(),
                httpChannel._responseHeaders,
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

                assert httpChannelState._failure == null;

                needLastStreamSend = httpChannelState.lockedLastStreamSend();
                completeStream = !needLastStreamSend && httpChannelState._handling == null && httpChannelState.lockedIsLastStreamSendCompleted();

                if (httpChannelState._responseHeaders.commit())
                    responseMetaData = response.lockedPrepareResponse(httpChannelState, true);

                long totalWritten = response._contentBytesWritten;
                long committedContentLength = httpChannelState._committedContentLength;

                if (committedContentLength >= 0 && committedContentLength != totalWritten)
                    failure = new IOException("content-length %d != %d written".formatted(committedContentLength, totalWritten));

                // is the request fully consumed?
                Throwable unconsumed = stream.consumeAvailable();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAvailable: {} {} ", unconsumed == null, httpChannelState);

                if (unconsumed != null && httpChannelState.getConnectionMetaData().isPersistent())
                    failure = ExceptionUtil.combine(failure, unconsumed);

                if (failure != null)
                {
                    httpChannelState._failure = failure;
                    if (!stream.isCommitted())
                        response.lockedPrepareErrorResponse();
                    else
                        completeStream = true;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("succeeded: failure={} needLastStreamSend={} {}", failure, needLastStreamSend, this);

            if (failure != null)
                Response.writeError(request, response, new ErrorCallback(request, stream, failure), failure);
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
            ChannelResponse response;
            HttpChannelState httpChannelState;
            boolean writeError;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannelState = _request._httpChannelState;
                stream = httpChannelState._stream;
                request = _request;
                response = httpChannelState._response;

                if (lockedCompleteCallback())
                    return;
                assert httpChannelState._failure == null;

                httpChannelState._failure = failure;

                // Consume any input.
                Throwable unconsumed = stream.consumeAvailable();
                if (ExceptionUtil.areNotAssociated(unconsumed, failure))
                    failure.addSuppressed(unconsumed);

                if (LOG.isDebugEnabled())
                    LOG.debug("failed stream.isCommitted={}, response.isCommitted={} {}", httpChannelState._stream.isCommitted(), httpChannelState._response.isCommitted(), this);

                writeError = !stream.isCommitted();
                if (writeError)
                    response.lockedPrepareErrorResponse();
            }

            if (writeError)
                Response.writeError(request, response, new ErrorCallback(request, stream, failure), failure);
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
     * Used as the {@link Response} and {@link Callback} when writing the error response
     * from {@link HttpChannelState.ChannelCallback#failed(Throwable)}.
     */
    private static class ErrorCallback implements Callback
    {
        private final ChannelRequest _request;
        private final HttpStream _stream;
        private final Throwable _failure;

        public ErrorCallback(ChannelRequest request, HttpStream stream, Throwable failure)
        {
            _request = request;
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
                if (needLastWrite && httpChannelState._responseHeaders.commit())
                    responseMetaData = httpChannelState._response.lockedPrepareResponse(httpChannelState, true);
            }

            if (needLastWrite)
            {
                _stream.send(_request._metaData, responseMetaData, true, null,
                    Callback.from(() -> httpChannelState._handlerInvoker.failed(failure),
                        x ->
                        {
                            if (ExceptionUtil.areNotAssociated(failure, x))
                                failure.addSuppressed(x);
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
            try (AutoLock ignored = _request._lock.lock())
            {
                failure = _failure;
            }
            if (ExceptionUtil.areNotAssociated(failure, x))
                failure.addSuppressed(x);
            _request.getHttpChannelState()._handlerInvoker.failed(failure);
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
            Content.Chunk.Error error;
            boolean callbackCompleted;
            try (AutoLock ignore = _lock.lock())
            {
                callbackCompleted = _callbackCompleted;
                request = _request;
                error = _request == null ? null : _error;
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
                    if (ExceptionUtil.areNotAssociated(failure, t))
                        failure.addSuppressed(t);
                    super.onError(task, failure);
                }
            }
            else
            {
                // We are already in error, so we will not handle this one,
                // but we will add as suppressed if we have not seen it already.
                Throwable cause = error.getCause();
                if (ExceptionUtil.areNotAssociated(cause, failure))
                    error.getCause().addSuppressed(failure);
            }
        }
    }
}
