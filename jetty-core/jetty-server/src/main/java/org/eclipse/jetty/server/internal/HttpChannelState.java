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
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Channel represents a sequence of request cycles from the same connection. However only a single
 * request cycle may be active at once for each channel.    This is some, but not all of the
 * behaviour of the current HttpChannel class, specifically it does not include the mutual exclusion
 * of handling required by the servlet spec and currently encapsulated in HttpChannelState.
 *
 * Note how Runnables are returned to indicate that further work is needed. These
 * can be given to an ExecutionStrategy instead of calling known methods like HttpChannel.handle().
 */
public class HttpChannelState implements HttpChannel, Components
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelState.class);
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

    enum State
    {
        /** Idle state */
        IDLE,

        /** The HandlerInvoker Runnable has been executed */
        HANDLING,

        /** A Request.Processor has been called.
         * Any calls to {@link #onFailure(Throwable)} will fail the callback. */
        PROCESSING,

        /** The Request.Processor call has returned prior to callback completion.
         * The Content.Reader APIs are enabled. */
        PROCESSED,

        /** Callback completion has been called prior to Request.Processor completion. */
        COMPLETED,

        /** The Request.Processor call has returned and the callback is complete */
        PROCESSED_AND_COMPLETED,
    }

    private final AutoLock _lock = new AutoLock();
    private final HandlerInvoker _handlerInvoker = new HandlerInvoker();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _serializedInvoker;
    private final Attributes _requestAttributes = new Attributes.Lazy();
    private final ResponseHttpFields _responseHeaders = new ResponseHttpFields();
    private State _state = State.IDLE; // TODO could this be an AtomicReference?
    private boolean _lastWrite = false;
    private Throwable _failure;
    private ChannelRequest _request;
    private HttpStream _stream;
    private long _committedContentLength = -1;
    private ResponseHttpFields _responseTrailers;
    private Runnable _onContentAvailable;
    private Callback _writeCallback;
    private Content.Error _error;
    private Predicate<Throwable> _onError;
    private Map<String, Object> _cache;

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
                Content.Error error;
                boolean completed;
                try (AutoLock ignore = _lock.lock())
                {
                    completed = _state.ordinal() >= State.COMPLETED.ordinal();
                    request = _request;
                    error = _request == null ? null : _error;
                }

                if (request == null || completed)
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
                        if (!TypeUtil.isAssociated(failure, t))
                            failure.addSuppressed(t);
                        super.onError(task, failure);
                    }
                }
                else
                {
                    // We are already in error, so we will not handle this one,
                    // but we will add as suppressed if we have not seen it already.
                    Throwable cause = error.getCause();
                    if (cause != null && !TypeUtil.isAssociated(cause, failure))
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
            _state = State.IDLE;
            _lastWrite = false;
            _failure = null;
            _committedContentLength = -1;
            if (_responseTrailers != null)
                _responseTrailers.reset();
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
    public Map<String, Object> getCache()
    {
        if (_cache == null)
        {
            if (getConnectionMetaData().isPersistent())
                _cache = new HashMap<>();
            else
                _cache = NULL_CACHE;
        }
        return _cache;
    }

    /**
     * Start request handling by returning a Runnable that will call {@link Handler#handle(Request)}.
     *
     * @param request The request metadata to handle.
     * @return A Runnable that will call {@link Handler#handle(Request)}.  Unlike all other Runnables
     * returned by HttpChannel methods, this runnable is not mutually excluded or serialized against the other
     * Runnables.
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

            if (!HttpMethod.PRI.is(request.getMethod()) &&
                !HttpMethod.CONNECT.is(request.getMethod()) &&
                !_request.getPathInContext().startsWith("/") &&
                !HttpMethod.OPTIONS.is(request.getMethod()))
                throw new BadMessageException("Bad URI path");

            HttpURI uri = request.getURI();
            if (uri.hasViolations())
            {
                String badMessage = UriCompliance.checkUriCompliance(getConnectionMetaData().getHttpConfiguration().getUriCompliance(), uri);
                if (badMessage != null)
                    throw new BadMessageException(badMessage);
            }

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

    public boolean isRequestHandled()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _state.ordinal() >= State.HANDLING.ordinal();
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
            LOG.debug("onError {}", this, x);

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
                _error = new Content.Error(x);
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
                // Only fail the callback if the application was not invoked.
                boolean handled;
                try (AutoLock ignore = _lock.lock())
                {
                    handled = _state.ordinal() >= State.HANDLING.ordinal();
                    if (!handled)
                        _state = State.PROCESSED;
                }
                if (handled)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already handled, skipping failing callback in {}", HttpChannelState.this);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failing callback in {}", HttpChannelState.this, x);
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
        Throwable unconsumed = stream.consumeAll();
        if (unconsumed != null && LOG.isDebugEnabled())
            LOG.debug("consuming content during error {}", unconsumed.toString());

        return task;
    }

    public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> onStreamEvent)
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
            HttpStream.Wrapper combined = onStreamEvent.apply(stream);
            if (combined == null || combined.getWrapped() != stream)
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

            _request._response._status = 0;

            _responseHeaders.clear();

            if (_responseTrailers != null)
                _responseTrailers.clear();
        }
    }

    private void changeState(State from, State to)
    {
        try (AutoLock ignored = _lock.lock())
        {
            changeStateLocked(from, to);
        }
    }

    private void changeStateLocked(State from, State to)
    {
        if (!_lock.isHeldByCurrentThread() || _state != from)  // TODO do we need the lock check?
            throw new IllegalStateException(String.valueOf(_state));
        _state = to;
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return String.format("%s@%x{s=%s,r=%s}", this.getClass().getSimpleName(), hashCode(), _state, _request);
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
                changeStateLocked(State.IDLE, State.HANDLING);
                request = _request;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("invoking handler in {}", HttpChannelState.this);
            Server server = _connectionMetaData.getConnector().getServer();

            Request.Processor processor;
            Request customized = request;
            Throwable failure = null;
            try
            {
                // Customize before accepting.
                HttpConfiguration configuration = getHttpConfiguration();

                for (HttpConfiguration.Customizer customizer : configuration.getCustomizers())
                {
                    Request next = customizer.customize(request, ((Response)request._response).getHeaders());
                    customized = next == null ? customized : next;
                }

                if (customized != request && server.getRequestLog() != null)
                    request.setLoggedRequest(customized);

                processor = server.handle(customized);
                if (processor == null)
                    processor = (req, res, cb) -> Response.writeError(req, res, cb, HttpStatus.NOT_FOUND_404);
            }
            catch (Throwable t)
            {
                processor = null;
                failure = t;
            }

            changeState(State.HANDLING, State.PROCESSING);

            try
            {
                if (processor != null)
                    processor.process(customized, request._response, request._callback);
            }
            catch (Throwable x)
            {
                failure = x;
            }
            if (failure != null)
                ((Callback)request._callback).failed(failure);

            HttpStream stream;
            boolean complete;
            try (AutoLock ignored = _lock.lock())
            {
                complete = _state == State.COMPLETED;

                if (_failure != null)
                {
                    if (failure != null)
                    {
                        if (!TypeUtil.isAssociated(failure, _failure))
                            _failure.addSuppressed(failure);
                    }
                    failure = _failure;
                }

                if (complete)
                    changeStateLocked(State.COMPLETED, State.PROCESSED_AND_COMPLETED);
                else
                    changeStateLocked(State.PROCESSING, State.PROCESSED);

                stream = _stream;
            }
            if (complete)
                complete(stream, failure);
        }

        /**
         * Called only as {@link Callback} by last write from {@link ChannelCallback#succeeded}
         */
        @Override
        public void succeeded()
        {
            HttpStream stream;
            boolean complete;
            try (AutoLock ignored = _lock.lock())
            {
                complete = _state == State.PROCESSED_AND_COMPLETED;
                stream = _stream;
            }
            if (complete)
                complete(stream, null);
        }

        /**
         * Called only as {@link Callback} by last write from {@link ChannelCallback#succeeded}
         */
        @Override
        public void failed(Throwable failure)
        {
            HttpStream stream;
            boolean complete;
            try (AutoLock ignored = _lock.lock())
            {
                complete = _state == State.PROCESSED_AND_COMPLETED;
                stream = _stream;
                if (_failure == null)
                    _failure = failure;
                else if (!TypeUtil.isAssociated(_failure, failure))
                {
                    _failure.addSuppressed(failure);
                    failure = _failure;
                }
            }
            if (complete)
                complete(stream, failure);
        }

        private void complete(HttpStream stream, Throwable failure)
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
                    // TODO _serializedInvoker.run(stream::succeeded);
                    //      That would wait for all callback to be completed.
                    stream.succeeded();
                else
                    stream.failed(_failure);
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
        private final ChannelResponse _response;
        private final AutoLock _lock;
        private final LongAdder _contentBytesRead = new LongAdder();
        private HttpChannelState _httpChannel;
        private Request _loggedRequest;

        ChannelRequest(HttpChannelState httpChannel, MetaData.Request metaData)
        {
            _httpChannel = Objects.requireNonNull(httpChannel);
            _id = httpChannel.getHttpStream().getId();
            _connectionMetaData = httpChannel.getConnectionMetaData();
            _metaData = Objects.requireNonNull(metaData);
            _response = new ChannelResponse(this);
            _lock = httpChannel._lock;
        }

        public void setLoggedRequest(Request request)
        {
            _loggedRequest = request;
        }

        public Request getLoggedRequest()
        {
            return _loggedRequest == null ? this : _loggedRequest;
        }

        HttpStream getStream()
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
        public String getPathInContext()
        {
            return _metaData.getURI().getCanonicalPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _metaData.getFields();
        }

        @Override
        public long getTimeStamp()
        {
            return _timeStamp;
        }

        @Override
        public boolean isSecure()
        {
            return HttpScheme.HTTPS.is(getHttpURI().getScheme());
        }

        @Override
        public long getContentLength()
        {
            return _metaData.getContentLength();
        }

        @Override
        public Content readContent()
        {
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannel();

                Content error = httpChannel._error;
                if (error != null)
                    return error;

                if (httpChannel._state.ordinal() < State.PROCESSING.ordinal())
                    return new Content.Error(new IllegalStateException("not processing"));

                stream = httpChannel._stream;
            }

            Content content = stream.readContent();
            if (content != null && content.hasRemaining())
                _contentBytesRead.add(content.remaining());

            return content;
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            boolean error;
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannelState httpChannel = lockedGetHttpChannel();

                error = httpChannel._error != null || httpChannel._state.ordinal() < State.PROCESSING.ordinal();
                if (!error)
                {
                    if (httpChannel._onContentAvailable != null)
                        throw new IllegalArgumentException("demand pending");
                    httpChannel._onContentAvailable = onContentAvailable;
                }

                stream = httpChannel._stream;
            }

            if (error)
                // TODO: can we avoid re-grabbing the lock to get the HttpChannel?
                getHttpChannel()._serializedInvoker.run(onContentAvailable);
            else
                stream.demandContent();
        }

        @Override
        public void push(MetaData.Request request)
        {
            getStream().push(request);
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
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
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
        private final ChannelRequest _request;
        private int _status;
        private long _contentBytesWritten;

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
        public HttpFields.Mutable getTrailers()
        {
            // TODO: getter with side effects, perhaps use a Supplier like in Jetty 11?
            try (AutoLock ignored = _request._lock.lock())
            {
                HttpChannelState httpChannel = _request.lockedGetHttpChannel();

                // TODO check if trailers allowed in version and transport?
                HttpFields.Mutable trailers = httpChannel._responseTrailers;
                if (trailers == null)
                    trailers = httpChannel._responseTrailers = new ResponseHttpFields();
                return trailers;
            }
        }

        private HttpFields takeTrailers()
        {
            ResponseHttpFields trailers = _request.getHttpChannel()._responseTrailers;
            if (trailers != null)
                trailers.commit();
            return trailers;
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            long written;
            HttpChannelState httpChannel;
            HttpStream stream = null;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();

                if (httpChannel._writeCallback != null)
                    failure = new IllegalStateException("write pending");
                else if (httpChannel._state.ordinal() < State.PROCESSING.ordinal())
                    failure = new IllegalStateException("not processing");
                else if (httpChannel._error != null)
                    failure = httpChannel._error.getCause();
                else if (last && httpChannel._lastWrite)
                    failure = new IllegalStateException("last already written");

                if (failure == null)
                {
                    httpChannel._writeCallback = callback;
                    for (ByteBuffer b : content)
                        _contentBytesWritten += b.remaining();

                    httpChannel._lastWrite |= last;

                    stream = httpChannel._stream;
                    written = _contentBytesWritten;

                    if (httpChannel._responseHeaders.commit())
                        responseMetaData = lockedPrepareResponse(httpChannel, last);

                    // If the content length were not compatible with what was written, then we need to abort.
                    long committedContentLength = httpChannel._committedContentLength;
                    if (committedContentLength >= 0)
                    {
                        String lengthError = (written > committedContentLength) ? "written %d > %d content-length"
                            : (last && written < committedContentLength) ? "written %d < %d content-length" : null;
                        if (lengthError != null)
                        {
                            String message = lengthError.formatted(written, committedContentLength);
                            if (LOG.isDebugEnabled())
                                LOG.debug("fail {} {}", callback, message);
                            failure = new IOException(message);
                        }
                    }
                }
            }

            if (failure != null)
            {
                Throwable t = failure;
                httpChannel._serializedInvoker.run(() -> callback.failed(t));
            }
            else
            {
                stream.send(_request._metaData, responseMetaData, last, this, content);
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
            }
            if (LOG.isDebugEnabled())
                LOG.debug("write succeeded {}", callback);
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
            try (AutoLock ignored = _request._lock.lock())
            {
                if (_request._httpChannel == null)
                    return false;
                return switch (_request._httpChannel._state)
                {
                    case COMPLETED, PROCESSED_AND_COMPLETED -> _request._httpChannel._failure == null;
                    default -> false;
                };
            }
        }

        @Override
        public void reset()
        {
            _request.getHttpChannel().resetResponse();
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

            // Provide trailers if they exist
            Supplier<HttpFields> trailers = httpChannel._responseTrailers == null ? null : this::takeTrailers;

            return new MetaData.Response(
                httpChannel.getConnectionMetaData().getHttpVersion(),
                _status,
                null,
                httpChannel._responseHeaders,
                httpChannel._committedContentLength,
                trailers
            );
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

        @Override
        public void succeeded()
        {
            // Called when the request/response cycle is completing successfully.
            HttpStream stream;
            boolean needLastWrite;
            HttpChannelState httpChannelState;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            boolean complete;
            try (AutoLock ignored = _request._lock.lock())
            {
                complete = complete();
                httpChannelState = _request._httpChannel;
                needLastWrite = !httpChannelState._lastWrite;

                // We are being tough on handler implementations and expect them
                // to not have pending operations when calling succeeded or failed.
                if (httpChannelState._onContentAvailable != null)
                    throw new IllegalStateException("demand pending");
                if (httpChannelState._writeCallback != null)
                    throw new IllegalStateException("write pending");
                if (httpChannelState._error != null)
                    throw new IllegalStateException("error " + httpChannelState._error, httpChannelState._error.getCause());

                stream = httpChannelState._stream;

                if (httpChannelState._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannelState, true);

                long written = _request._response._contentBytesWritten;
                long committedContentLength = httpChannelState._committedContentLength;

                if (committedContentLength >= 0 && committedContentLength != written)
                    failure = httpChannelState._failure = new IOException("content-length %d != %d written".formatted(committedContentLength, written));

                // is the request fully consumed?
                Throwable unconsumed = stream.consumeAll();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAll: {} {} ", unconsumed == null, httpChannelState);

                if (unconsumed != null && httpChannelState.getConnectionMetaData().isPersistent())
                {
                    if (failure == null)
                        failure = httpChannelState._failure = unconsumed;
                    else if (!TypeUtil.isAssociated(failure, unconsumed))
                        failure.addSuppressed(unconsumed);
                }
            }

            if (failure == null && needLastWrite)
                stream.send(_request._metaData, responseMetaData, true, httpChannelState._handlerInvoker);
            else if (complete)
                httpChannelState._handlerInvoker.complete(stream, failure);
        }

        @Override
        public void failed(Throwable failure)
        {
            // Called when the request/response cycle is completing with a failure.
            HttpStream stream;
            boolean writeErrorResponse;
            ChannelRequest request;
            HttpChannelState httpChannelState;
            boolean complete;
            try (AutoLock ignored = _request._lock.lock())
            {
                complete = complete();
                httpChannelState = _request._httpChannel;
                httpChannelState._failure = failure;

                // Verify whether we can write an error response.
                writeErrorResponse = !httpChannelState._stream.isCommitted();
                stream = httpChannelState._stream;
                request = _request;

                // Consume any input.
                Throwable unconsumed = stream.consumeAll();
                if (unconsumed != null && !TypeUtil.isAssociated(unconsumed, failure))
                    failure.addSuppressed(unconsumed);

                if (writeErrorResponse)
                {
                    // Cannot log or recycle just yet, since we need to generate the error response.
                    _request._response._status = HttpStatus.INTERNAL_SERVER_ERROR_500;
                    httpChannelState._responseHeaders.reset();
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("failed {}", httpChannelState, failure);

            if (writeErrorResponse)
            {
                ErrorResponse responseAndCallback = new ErrorResponse(request, stream);
                Response.writeError(request, responseAndCallback, httpChannelState._handlerInvoker, failure);
            }
            else if (complete)
            {
                httpChannelState._handlerInvoker.complete(stream, failure);
            }
        }

        private boolean complete()
        {
            HttpChannelState httpChannelState = _request._httpChannel;
            if (httpChannelState == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("already recycled after completion {} by", _request, _completedBy);
                throw new IllegalStateException("channel already completed");
            }

            return switch (httpChannelState._state)
            {
                case PROCESSING ->
                {
                    if (LOG.isDebugEnabled())
                        _completedBy = new Throwable(Thread.currentThread().getName());
                    httpChannelState._state = State.COMPLETED;
                    yield false;
                }
                case PROCESSED ->
                {
                    if (LOG.isDebugEnabled())
                        _completedBy = new Throwable(Thread.currentThread().getName());
                    httpChannelState._state = State.PROCESSED_AND_COMPLETED;
                    yield true;
                }
                case PROCESSED_AND_COMPLETED, COMPLETED ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("already completed {} by", _request, _completedBy);
                    throw new IllegalStateException("channel is completing");
                }
                default -> throw new IllegalStateException("not processing");
            };
        }

        @Override
        public InvocationType getInvocationType()
        {
            // TODO review this as it is probably not correct
            return _request.getStream().getInvocationType();
        }
    }

    private static class ErrorResponse extends Response.Wrapper
    {
        private final ChannelRequest _request;
        private final ChannelResponse _response;
        private final HttpStream _stream;

        public ErrorResponse(ChannelRequest request, HttpStream stream)
        {
            super(request, request._response);
            _request = request;
            _response = request._response;
            _stream = stream;
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                HttpChannelState httpChannel = _request.lockedGetHttpChannel();

                httpChannel._writeCallback = callback;
                for (ByteBuffer b : content)
                    _response._contentBytesWritten += b.remaining();

                httpChannel._lastWrite |= last;

                if (httpChannel._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannel, last);
            }
            _stream.send(_request._metaData, responseMetaData, last, callback, content);
        }
    }
}
