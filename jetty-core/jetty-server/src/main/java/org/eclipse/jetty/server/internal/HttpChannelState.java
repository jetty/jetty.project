//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelState.class);
    private static final Throwable DO_NOT_SEND = new Throwable("No Send");
    private static final HttpField CONTENT_LENGTH_0 = new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH, "0")
    {
        @Override
        public int getIntValue()
        {
            return 0;
        }

        @Override
        public long getLongValue()
        {
            return 0L;
        }
    };
    private static final MetaData.Request ERROR_REQUEST = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_1_0, HttpFields.EMPTY);
    private static final HttpField SERVER_VERSION = new PreEncodedHttpField(HttpHeader.SERVER, HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY = new PreEncodedHttpField(HttpHeader.X_POWERED_BY, HttpConfiguration.SERVER_VERSION);
    private static final Map<String, Object> NULL_CACHE = new AbstractMap<>()
    {
        @Override
        public Set<Entry<String, Object>> entrySet()
        {
            return Collections.emptySet();
        }

        @Override
        public Object put(String key, Object value)
        {
            return null;
        }

        @Override
        public void putAll(Map<? extends String, ?> m)
        {
        }
    };
    private static final Throwable ACCEPTED = new Throwable()
    {
        @Override
        public Throwable fillInStackTrace()
        {
            return this;
        }
    };

    /**
     * The state of the written response
     */
    enum WriteState
    {
        /** Not yet written */
        NOT_LAST,

        /** Last content written, but write not yet completed */
        LAST_WRITTEN,

        /** Last content written and completed */
        LAST_WRITE_COMPLETED,
    }

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _serializedInvoker;
    private final Attributes _requestAttributes = new Attributes.Lazy();
    private final ResponseHttpFields _responseHeaders = new ResponseHttpFields();
    private Thread _processing;
    private Throwable _accepted;
    private WriteState _writeState = WriteState.NOT_LAST;
    private boolean _callbackCompleted = false;
    private Throwable _failure;
    private ChannelRequest _request;
    private HttpStream _stream;
    private long _committedContentLength = -1;
    private Runnable _onContentAvailable;
    private Callback _writeCallback;
    private Content.Chunk.Error _error;
    private Predicate<Throwable> _onError;
    private Attributes _cache;

    public HttpChannelState(ConnectionMetaData connectionMetaData)
    {
        _connectionMetaData = connectionMetaData;
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _serializedInvoker = new SerializedInvoker()
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
                    if (cause != null && ExceptionUtil.areNotAssociated(cause, failure))
                        error.getCause().addSuppressed(failure);
                }
            }
        };
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
            _request._httpChannel = null;

            // Break the links with the upper and lower layers.
            _request = null;
            _stream = null;

            // Recycle.
            _requestAttributes.clearAttributes();
            _responseHeaders.reset();
            _processing = null;
            _accepted = null;
            _writeState = WriteState.NOT_LAST;
            _callbackCompleted = false;
            _failure = null;
            _committedContentLength = -1;
            _onContentAvailable = null;
            _writeCallback = null;
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
        return getServer().getBean(Scheduler.class);
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
     * Start request handling by returning a Runnable that will call {@link Handler#process(Request, Response, Callback)}.
     *
     * @param request The request metadata to handle.
     * @return A Runnable that will call {@link Handler#process(Request, Response, Callback)}.  Unlike all other {@link Runnable}s
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

            HttpFields.Mutable responseHeaders = _request._response.getHeaders();
            if (getHttpConfiguration().getSendServerVersion())
                responseHeaders.add(SERVER_VERSION);
            if (getHttpConfiguration().getSendXPoweredBy())
                responseHeaders.add(POWERED_BY);
            if (getHttpConfiguration().getSendDateHeader())
                responseHeaders.add(getConnectionMetaData().getConnector().getServer().getDateField());

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
            return _request == null ? null : _request._response;
        }
    }

    public boolean isRequestAccepted()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _accepted != null;
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
            }

            // Remember the error and arrange for any subsequent reads, demands or writes to fail with this error.
            if (_error == null)
            {
                _error = Content.Chunk.from(x);
            }
            else if (_error.getCause() != x)
            {
                _error.getCause().addSuppressed(x);
                return null;
            }

            // Invoke onContentAvailable() if we are currently demanding.
            Runnable invokeOnContentAvailable = _onContentAvailable;
            _onContentAvailable = null;

            // If a write() is in progress, fail the write callback.
            Callback writeCallback = _writeCallback;
            _writeCallback = null;
            Runnable invokeWriteFailure = writeCallback == null ? null : () -> writeCallback.failed(x);

            ChannelRequest request = _request;
            Runnable invokeCallback = () ->
            {
                // Only fail the callback if the request was not accepted.
                Throwable accepted;
                try (AutoLock ignore = _lock.lock())
                {
                    accepted = _accepted;
                }
                if (accepted != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already accepted, skipping failing callback in {}", HttpChannelState.this, accepted);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failing callback in {}", this, x);
                    request._callback.failed(x);
                }
            };

            // Invoke error listeners.
            Predicate<Throwable> onError = _onError;
            _onError = null;
            Runnable invokeOnErrorAndCallback = onError == null ? invokeCallback : () ->
            {
                if (!onError.test(x))
                    invokeCallback.run();
            };

            // Serialize all the error actions.
            task = _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnErrorAndCallback);
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

    private Throwable lockedCheckWrite(boolean last, long length)
    {
        assert _request._lock.isHeldByCurrentThread();

        return switch (_writeState)
        {
            case NOT_LAST ->
            {
                _writeState = last ? WriteState.LAST_WRITTEN : WriteState.NOT_LAST;
                _request._response._contentBytesWritten += length;
                yield null;
            }

            // There are many instances of code that wants to ensure the output is closed, so
            // it does a redundant write(true, callback).  The DO_NOT_SEND option supports this by
            // turning such writes into a NOOP.
            case LAST_WRITTEN, LAST_WRITE_COMPLETED -> (length > 0)
                ? new IllegalStateException("last already written")
                : DO_NOT_SEND;
        };
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return String.format("%s@%x{processing=%s, accepted=%b, writeState=%s, completed=%b, writeCallback=%s, request=%s}",
                this.getClass().getSimpleName(),
                hashCode(),
                _processing,
                _accepted,
                _writeState,
                _callbackCompleted,
                _writeCallback,
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
            try (AutoLock ignored = _lock.lock())
            {
                assert _processing == null && _accepted == null;
                _processing = Thread.currentThread();
                request = _request;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("invoking handler in {}", HttpChannelState.this);
            Server server = _connectionMetaData.getConnector().getServer();

            Request customized = request;
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

                HttpFields.Mutable responseHeaders = request._response.getHeaders();
                for (HttpConfiguration.Customizer customizer : configuration.getCustomizers())
                {
                    Request next = customizer.customize(customized, responseHeaders);
                    customized = next == null ? customized : next;
                }

                if (customized != request && server.getRequestLog() != null)
                    request.setLoggedRequest(customized);

                server.process(customized, request._response, request._callback);
            }
            catch (Throwable t)
            {
                failure = t;
            }

            boolean accepted;
            boolean complete;
            HttpStream stream;
            boolean completeStream = false;

            try (AutoLock ignored = _lock.lock())
            {
                stream = _stream;
                accepted = _accepted != null;
                if (!accepted)
                    _accepted = LOG.isDebugEnabled() ? new Throwable("Accept") : ACCEPTED;

                complete = accepted && failure == null;
                if (complete)
                {
                    _processing = null;
                    failure = ExceptionUtil.combine(_failure, failure);
                    completeStream = _callbackCompleted && (failure != null || _writeState == WriteState.LAST_WRITE_COMPLETED);
                }
            }

            if (!complete)
            {
                if (failure == null)
                {
                    try
                    {
                        Response.writeError(request, request._response, request._callback, HttpStatus.NOT_FOUND_404);
                    }
                    catch (Throwable x)
                    {
                        failure = x;
                    }
                }

                if (failure != null)
                    request._callback.failed(failure);

                try (AutoLock ignored = _lock.lock())
                {
                    _processing = null;

                    failure = ExceptionUtil.combine(_failure, failure);
                    completeStream = _callbackCompleted && (failure != null || _writeState == WriteState.LAST_WRITE_COMPLETED);
                }
            }
            if (completeStream)
                completeStream(stream, failure);
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
                if (_accepted == null)
                    throw new IllegalStateException("!accepted");
                _writeState = WriteState.LAST_WRITE_COMPLETED;
                assert _callbackCompleted;
                completeStream = _processing == null;
                stream = _stream;
            }

            if (completeStream)
                completeStream(stream, null);
        }

        /**
         * Called only as {@link Callback} by last write from {@link ChannelCallback#succeeded}
         */
        @Override
        public void failed(Throwable failure)
        {
            HttpStream stream;
            boolean completeStream;
            try (AutoLock ignored = _lock.lock())
            {
                if (_accepted == null)
                    throw new IllegalStateException("!accepted");
                _writeState = WriteState.LAST_WRITE_COMPLETED;
                assert _callbackCompleted;
                completeStream = _processing == null;
                stream = _stream;
                if (_failure == null)
                    _failure = failure;
                else if (ExceptionUtil.areNotAssociated(_failure, failure))
                {
                    _failure.addSuppressed(failure);
                    failure = _failure;
                }
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

                    requestLog.log(_request.getLoggedRequest(), _request._response);
                }
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
        private static final Logger LOG = LoggerFactory.getLogger(ChannelResponse.class);

        private final long _timeStamp = System.currentTimeMillis();
        private final ChannelCallback _callback = new ChannelCallback(this);
        private final String _id;
        private final ConnectionMetaData _connectionMetaData;
        private final MetaData.Request _metaData;
        private final ChannelResponse _response;
        private final AutoLock _lock;
        private final LongAdder _contentBytesRead = new LongAdder();
        private HttpChannelState _httpChannel;
        private Request _loggedRequest;
        private HttpFields _trailers;

        ChannelRequest(HttpChannelState httpChannel, MetaData.Request metaData)
        {
            _httpChannel = Objects.requireNonNull(httpChannel);
            _id = httpChannel.getHttpStream().getId();
            _connectionMetaData = httpChannel.getConnectionMetaData();
            _metaData = Objects.requireNonNull(metaData);
            _response = new ChannelResponse(this);
            _lock = httpChannel._lock;
        }

        @Override
        public void accept()
        {
            try (AutoLock ignore = _lock.lock())
            {
                if (_httpChannel._processing != Thread.currentThread())
                    throw new IllegalStateException(_httpChannel._processing == null ? "Not processing" : "Not processing by current thread");
                if (_httpChannel._accepted != null)
                    throw new IllegalStateException("already accepted", _httpChannel._accepted);
                _httpChannel._accepted = LOG.isDebugEnabled() ? new Throwable("Accept") : ACCEPTED;
            }
        }

        @Override
        public boolean isAccepted()
        {
            try (AutoLock ignore = _lock.lock())
            {
                return _httpChannel._accepted != null;
            }
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
            return getHttpChannel()._stream;
        }

        public long getContentBytesRead()
        {
            return _contentBytesRead.longValue();
        }

        @Override
        public Object getAttribute(String name)
        {
            HttpChannelState httpChannel = getHttpChannel();
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
            return getHttpChannel()._requestAttributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (Server.class.getName().equals(name) || HttpChannelState.class.getName().equals(name) || HttpConnection.class.getName().equals(name))
                return null;
            return getHttpChannel()._requestAttributes.setAttribute(name, attribute);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return getHttpChannel()._requestAttributes.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            getHttpChannel()._requestAttributes.clearAttributes();
        }

        @Override
        public String getId()
        {
            return _id;
        }

        @Override
        public Components getComponents()
        {
            return getHttpChannel();
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return _connectionMetaData;
        }

        HttpChannelState getHttpChannel()
        {
            try (AutoLock ignore = _lock.lock())
            {
                return lockedGetHttpChannel();
            }
        }

        private HttpChannelState lockedGetHttpChannel()
        {
            assert _lock.isHeldByCurrentThread();
            if (_httpChannel == null)
                throw new IllegalStateException("channel already completed");
            return _httpChannel;
        }

        @Override
        public String getMethod()
        {
            return _metaData.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _metaData.getURI();
        }

        @Override
        public Context getContext()
        {
            return getConnectionMetaData().getConnector().getServer().getContext();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _metaData.getFields();
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
        public long getNanoTimeStamp()
        {
            HttpStream stream = _httpChannel.getHttpStream();
            if (stream != null)
                return stream.getNanoTimeStamp();

            return System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis() - getTimeStamp());
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
                HttpChannelState httpChannel = lockedGetHttpChannel();

                Content.Chunk error = httpChannel._error;
                if (error != null)
                    return error;

                if (httpChannel._accepted == null)
                    return Content.Chunk.from(new IllegalStateException("not accepted"));

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
        public void demand(Runnable demandCallback)
        {
            boolean error;
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannel();

                error = httpChannel._error != null || httpChannel._accepted == null;
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
                getHttpChannel()._serializedInvoker.run(demandCallback);
            else
                stream.demand();
        }

        @Override
        public void fail(Throwable failure)
        {
            // TODO
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
                HttpChannelState httpChannel = lockedGetHttpChannel();

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
            getHttpChannel().addHttpStreamWrapper(wrapper);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x %s %s", getMethod(), hashCode(), getHttpURI(), _metaData.getHttpVersion());
        }
    }

    public static class ChannelResponse implements Response, Callback
    {
        private static final Logger LOG = LoggerFactory.getLogger(ChannelResponse.class);

        private final ChannelRequest _request;
        private int _status;
        private long _contentBytesWritten;
        private Supplier<HttpFields> _trailers;

        private ChannelResponse(ChannelRequest request)
        {
            _request = request;
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
            return _request.getHttpChannel()._responseHeaders;
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

            long totalWritten;
            HttpChannelState httpChannel;
            HttpStream stream = null;
            Throwable failure;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();

                if (httpChannel._writeCallback != null)
                    failure = new IllegalStateException("write pending");
                else if (httpChannel._accepted == null)
                    failure = new IllegalStateException("not accepted");
                else if (httpChannel._error != null)
                    failure = httpChannel._error.getCause();
                else
                    failure = httpChannel.lockedCheckWrite(last, length);

                if (failure == null)
                {
                    httpChannel._writeCallback = callback;

                    stream = httpChannel._stream;
                    totalWritten = _contentBytesWritten;

                    if (httpChannel._responseHeaders.commit())
                        responseMetaData = lockedPrepareResponse(httpChannel, last);

                    // If the content length were not compatible with what was written, then we need to abort.
                    long committedContentLength = httpChannel._committedContentLength;
                    if (committedContentLength >= 0)
                    {
                        String lengthError = (totalWritten > committedContentLength) ? "written %d > %d content-length"
                            : (last && totalWritten < committedContentLength) ? "written %d < %d content-length" : null;
                        if (lengthError != null)
                        {
                            String message = lengthError.formatted(totalWritten, committedContentLength);
                            if (LOG.isDebugEnabled())
                                LOG.debug("fail {} {}", callback, message);
                            failure = new IOException(message);
                        }
                    }
                }
            }

            if (failure == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("writing last={} {} {}", last, BufferUtil.toDetailString(content), this);
                stream.send(_request._metaData, responseMetaData, last, content, this);
            }
            else if (failure == DO_NOT_SEND)
            {
                httpChannel._serializedInvoker.run(callback::succeeded);
            }
            else
            {
                Throwable t = failure;
                httpChannel._serializedInvoker.run(() -> callback.failed(t));
            }
        }

        @Override
        public void succeeded()
        {
            // Called when an individual write succeeds.
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();
                callback = httpChannel._writeCallback;
                httpChannel._writeCallback = null;
                if (httpChannel._writeState == WriteState.LAST_WRITTEN)
                    httpChannel._writeState = WriteState.LAST_WRITE_COMPLETED;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("write succeeded {} {}", callback, this);
            if (callback != null)
                httpChannel._serializedInvoker.run(callback::succeeded);
        }

        @Override
        public void failed(Throwable x)
        {
            // Called when an individual write fails.
            Callback callback;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();
                callback = httpChannel._writeCallback;
                httpChannel._writeCallback = null;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("write failed {}", callback, x);
            if (callback != null)
                httpChannel._serializedInvoker.run(() -> callback.failed(x));
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(_request.getHttpChannel()._writeCallback);
        }

        @Override
        public boolean isCommitted()
        {
            return _request.getHttpChannel()._responseHeaders.isCommitted();
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            // TODO: this should return whether the last write (or the stream) is completed
            //  not _completed because the last write may still be pending.
            try (AutoLock ignored = _request._lock.lock())
            {
                if (_request._httpChannel == null)
                    return false;

                return _request._httpChannel._callbackCompleted && _request._httpChannel._failure == null;
            }
        }

        @Override
        public void reset()
        {
            _status = 0;
            _trailers = null;
            _request.getHttpChannel().resetResponse();
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            Completable completable = new Completable();
            if (HttpStatus.isInterim(status))
            {
                HttpChannelState channel = _request.getHttpChannel();
                HttpVersion version = channel.getConnectionMetaData().getHttpVersion();
                MetaData.Response response = new MetaData.Response(version, status, headers);
                channel._stream.send(_request._metaData, response, false, null, completable);
            }
            else
            {
                completable.failed(new IllegalArgumentException("Invalid interim status code: " + status));
            }
            return completable;
        }

        private MetaData.Response lockedPrepareResponse(HttpChannelState httpChannel, boolean last)
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
                if (httpChannel._committedContentLength == 0)
                    mutableHeaders.put(CONTENT_LENGTH_0);
                else
                    mutableHeaders.putLongField(HttpHeader.CONTENT_LENGTH, httpChannel._committedContentLength);
            }

            httpChannel._stream.prepareResponse(mutableHeaders);

            return new MetaData.Response(
                httpChannel.getConnectionMetaData().getHttpVersion(),
                _status,
                null,
                httpChannel._responseHeaders,
                httpChannel._committedContentLength,
                getTrailersSupplier()
            );
        }
    }

    private static class ChannelCallback implements Callback
    {
        private static final Logger LOG = LoggerFactory.getLogger(ChannelCallback.class);

        private final ChannelRequest _request;
        private Throwable _completedBy;

        private ChannelCallback(ChannelRequest request)
        {
            _request = request;
        }

        @Override
        public void succeeded()
        {
            // Called when the request/response cycle is completing successfully.
            HttpStream stream;
            boolean needLastWrite;
            HttpChannelState httpChannelState;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            boolean completeStream;
            try (AutoLock ignored = _request._lock.lock())
            {
                if (!lockedOnComplete())
                    return;
                httpChannelState = _request._httpChannel;
                completeStream =
                    httpChannelState._accepted != null &&
                    httpChannelState._processing == null &&
                    httpChannelState._writeState == WriteState.LAST_WRITE_COMPLETED;

                // We are being tough on handler implementations and expect them
                // to not have pending operations when calling succeeded or failed.
                if (httpChannelState._onContentAvailable != null)
                    throw new IllegalStateException("demand pending");
                if (httpChannelState._writeCallback != null)
                    throw new IllegalStateException("write pending");
                // Here, httpChannelState._error might have been set by some
                // asynchronous event such as an idle timeout, and that's ok.

                needLastWrite = switch (httpChannelState._writeState)
                {
                    case NOT_LAST -> true;
                    case LAST_WRITTEN, LAST_WRITE_COMPLETED -> false;
                };
                stream = httpChannelState._stream;

                if (httpChannelState._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannelState, true);

                long totalWritten = _request._response._contentBytesWritten;
                long committedContentLength = httpChannelState._committedContentLength;

                if (committedContentLength >= 0 && committedContentLength != totalWritten)
                    failure = httpChannelState._failure = new IOException("content-length %d != %d written".formatted(committedContentLength, totalWritten));

                // is the request fully consumed?
                Throwable unconsumed = stream.consumeAvailable();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAvailable: {} {} ", unconsumed == null, httpChannelState);

                if (unconsumed != null && httpChannelState.getConnectionMetaData().isPersistent())
                {
                    if (failure == null)
                        failure = httpChannelState._failure = unconsumed;
                    else if (ExceptionUtil.areNotAssociated(failure, unconsumed))
                        failure.addSuppressed(unconsumed);
                }
            }

            if (failure == null && needLastWrite)
                stream.send(_request._metaData, responseMetaData, true, null, httpChannelState._handlerInvoker);
            else if (completeStream)
                httpChannelState._handlerInvoker.completeStream(stream, failure);
        }

        @Override
        public void failed(Throwable failure)
        {
            // Called when the request/response cycle is completing with a failure.
            HttpStream stream;
            boolean writeErrorResponse;
            ChannelRequest request;
            HttpChannelState httpChannelState;
            boolean completeStream;
            try (AutoLock ignored = _request._lock.lock())
            {
                if (!lockedOnComplete())
                    return;
                httpChannelState = _request._httpChannel;
                httpChannelState._failure = failure;
                completeStream = httpChannelState._processing == null;

                // Verify whether we can write an error response.
                writeErrorResponse = !httpChannelState._stream.isCommitted();
                stream = httpChannelState._stream;
                request = _request;

                // Consume any input.
                Throwable unconsumed = stream.consumeAvailable();
                if (unconsumed != null && ExceptionUtil.areNotAssociated(unconsumed, failure))
                    failure.addSuppressed(unconsumed);

                if (writeErrorResponse)
                {
                    // Cannot log or recycle just yet, since we need to generate the error response.
                    _request._response._status = HttpStatus.INTERNAL_SERVER_ERROR_500;
                    if (httpChannelState._accepted == null)
                        httpChannelState._accepted = LOG.isDebugEnabled() ? new Throwable("Accept") : ACCEPTED;
                    httpChannelState._responseHeaders.reset();
                    httpChannelState._writeState = WriteState.NOT_LAST;
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("failed {}", httpChannelState, failure);

            if (writeErrorResponse)
            {
                ErrorResponse response = new ErrorResponse(request, stream, failure);
                Response.writeError(request, response, response, failure);
            }
            else if (completeStream)
            {
                httpChannelState._handlerInvoker.completeStream(stream, failure);
            }
        }

        private boolean lockedOnComplete()
        {
            assert _request._lock.isHeldByCurrentThread();

            HttpChannelState httpChannelState = _request._httpChannel;
            if (httpChannelState == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("already recycled after completion {} by", _request, _completedBy);
                return false;
            }

            if (httpChannelState._callbackCompleted)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("already completed {} by", _request, _completedBy);
                return false;
            }

            if (LOG.isDebugEnabled())
                _completedBy = new Throwable(Thread.currentThread().getName());

            httpChannelState._callbackCompleted = true;

            return true;
        }

        @Override
        public InvocationType getInvocationType()
        {
            // TODO review this as it is probably not correct
            return _request.getHttpStream().getInvocationType();
        }
    }

    private static class ErrorResponse extends Response.Wrapper implements Callback
    {
        private final ChannelRequest _request;
        private final HttpStream _stream;
        private final Throwable _failure;

        public ErrorResponse(ChannelRequest request, HttpStream stream, Throwable failure)
        {
            super(request, request._response);
            _request = request;
            _stream = stream;
            _failure = failure;
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback)
        {
            long length = BufferUtil.length(content);

            HttpChannelState httpChannel;
            Throwable failure;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();
                httpChannel._writeCallback = callback;
                failure = httpChannel.lockedCheckWrite(last, length);
                if (httpChannel._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannel, last);
            }

            if (failure == null)
                _stream.send(_request._metaData, responseMetaData, last, content, last ? Callback.from(this::lastWriteCompleted, callback) : callback);
            else if (failure == DO_NOT_SEND)
                httpChannel._serializedInvoker.run(callback::succeeded);
            else
                httpChannel._serializedInvoker.run(() -> callback.failed(failure));
        }

        private void lastWriteCompleted()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                _request.lockedGetHttpChannel()._writeState = WriteState.LAST_WRITE_COMPLETED;
            }
        }

        @Override
        public void succeeded()
        {
            boolean needLastWrite;
            MetaData.Response responseMetaData = null;
            HttpChannelState httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.getHttpChannel();

                // Did the errorProcessor do the last write?
                needLastWrite = httpChannel._writeState.ordinal() <= WriteState.LAST_WRITTEN.ordinal();
                if (needLastWrite && httpChannel._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannel, true);
            }

            if (needLastWrite)
            {
                _stream.send(_request._metaData, responseMetaData, true, null,
                    Callback.from(() -> httpChannel._handlerInvoker.failed(_failure),
                        x ->
                        {
                            if (ExceptionUtil.areNotAssociated(_failure, x))
                                _failure.addSuppressed(x);
                            httpChannel._handlerInvoker.failed(_failure);
                        }));
            }
            else
            {
                httpChannel._handlerInvoker.failed(_failure);
            }
        }

        @Override
        public void failed(Throwable x)
        {
            if (ExceptionUtil.areNotAssociated(_failure, x))
                _failure.addSuppressed(x);
            _request.getHttpChannel()._handlerInvoker.failed(_failure);
        }
    }
}
