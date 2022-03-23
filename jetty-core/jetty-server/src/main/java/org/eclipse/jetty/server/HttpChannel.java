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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
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
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.ResponseHttpFields;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedInvoker;
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
public class HttpChannel
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = HttpChannel.class.getName() + ".UPGRADE";
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);
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

    private final AutoLock _lock = new AutoLock();
    private final Runnable _handlerInvoker = new HandlerInvoker();
    private final ConnectionMetaData _connectionMetaData;
    private final SerializedInvoker _serializedInvoker;
    private final Attributes _requestAttributes = new Attributes.Lazy();
    private final ResponseHttpFields _responseHeaders = new ResponseHttpFields();
    private long _requests;
    private ChannelRequest _request;
    private HttpStream _stream;
    private boolean _processing;
    private boolean _handled;
    private long _contentBytesRead;
    private long _contentBytesWritten;
    private long _committedContentLength = -1;
    private ResponseHttpFields _responseTrailers;
    private Runnable _onContentAvailable;
    private Callback _writeCallback;
    private boolean _lastWrite;
    private Content.Error _error;
    private Predicate<Throwable> _onError;

    public HttpChannel(ConnectionMetaData connectionMetaData)
    {
        _connectionMetaData = connectionMetaData;
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _serializedInvoker = new SerializedInvoker()
        {
            @Override
            protected void onError(Runnable task, Throwable t)
            {
                Content.Error error;
                try (AutoLock ignore = _lock.lock())
                {
                    error = _request == null ? null : _error;
                }

                if (error != null)
                {
                    // We are already in error, so we will not handle this one,
                    // but we will add as suppressed if we have not seen it already.
                    Throwable cause = error.getCause();
                    if (cause != null && !TypeUtil.isAssociated(cause, t))
                        error.getCause().addSuppressed(t);
                    return;
                }
                super.onError(task, t);
            }
        };
    }

    private void lockedRecycle()
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
        _processing = false;
        _handled = false;
        _contentBytesRead = 0;
        _contentBytesWritten = 0;
        _committedContentLength = -1;
        if (_responseTrailers != null)
            _responseTrailers.reset();
        _onContentAvailable = null;
        _writeCallback = null;
        _lastWrite = false;
        _error = null;
        _onError = null;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _connectionMetaData.getHttpConfiguration();
    }

    public long getRequests()
    {
        return _requests;
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
            if (_stream != null)
                throw new IllegalStateException("stream pending");
            _stream = stream;
        }
    }

    public Server getServer()
    {
        return _connectionMetaData.getConnector().getServer();
    }

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
            if (_request != null)
                throw new IllegalStateException("duplicate request");

            _request = new ChannelRequest(this, request);
            _requests++;
            ChannelRequest capturedRequest = _request;
            RequestLog requestLog = getConnectionMetaData().getConnector().getServer().getRequestLog();
            if (requestLog != null)
            {
                // TODO: use addStreamWrapper() instead.
                // TODO: make this efficient.
                _stream = new HttpStream.Wrapper(_stream)
                {
                    @Override
                    public void succeeded()
                    {
                        try
                        {
                            requestLog.log(capturedRequest, capturedRequest._response);
                        }
                        catch (Throwable t)
                        {
                            LOG.warn("RequestLog Error", t);
                        }

                        super.succeeded();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        try
                        {
                            requestLog.log(capturedRequest, capturedRequest._response);
                        }
                        catch (Throwable t)
                        {
                            LOG.warn("RequestLog Error", t);
                        }

                        super.failed(x);
                    }
                };
            }

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

    public boolean isHandled()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _handled;
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

    public Invocable.InvocationType getOnContentAvailableInvocationType()
    {
        // TODO Can this actually be done, as we may need to invoke other Runnables after onContent?
        Runnable onContent;
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return null;
            onContent = _onContentAvailable;
        }
        return Invocable.getInvocationType(onContent);
    }

    public Runnable onError(Throwable x)
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
                _processing = true;
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
                    handled = _handled;
                    _handled = true;
                    if (!handled)
                        _processing = true;
                }
                if (handled)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("already handled, skipping failing callback in {}", HttpChannel.this);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failing callback in {}", HttpChannel.this, x);
                    request._callback.failed(x);
                }
            };

            // Invoke error listeners.
            Predicate<Throwable> onError = _onError;
            _onError = null;
            Runnable invokeOnError = () ->
            {
                if (onError == null || !onError.test(x))
                    invokeCallback.run();
            };

            // Serialize all the error actions.
            task = _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnError);
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

    /**
     * Format the address or host returned from Request methods
     *
     * @param addr The address or host
     * @return Default implementation returns {@link HostPort#normalizeHost(String)}
     */
    protected String formatAddrOrHost(String addr)
    {
        return HostPort.normalizeHost(addr);
    }

    public void enableProcessing()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_processing)
                throw new IllegalStateException("request already processing " + this);
            // TODO: use system property to record what thread accepted it.
            _processing = true;
        }
    }

    private void reset()
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

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s}", this.getClass().getSimpleName(), hashCode(), _request);
    }

    private class HandlerInvoker implements Invocable.Task
    {
        @Override
        public void run()
        {
            // The chance to complete the callback is either given
            // to the application, or the implementation does it.
            ChannelRequest request;
            try (AutoLock ignored = _lock.lock())
            {
                request = _handled ? null : _request;
                _handled = true;
            }
            // Don't call the application if an error already completed this channel.
            if (request != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("invoking handler in {}", HttpChannel.this);
                getConnectionMetaData().getConnector().getServer().customizeHandleAndProcess(request, request._response, request._callback);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("already handled, skipping handler invocation in {}", HttpChannel.this);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getConnectionMetaData().getConnector().getServer().getInvocationType();
        }
    }

    static class ChannelRequest implements Attributes, Request
    {
        private final long _timeStamp = System.currentTimeMillis();
        private final Callback _callback = new ChannelCallback(this);
        private final String _id;
        private final ConnectionMetaData _connectionMetaData;
        private final MetaData.Request _metaData;
        private final ChannelResponse _response;
        private final AutoLock _lock;
        private HttpChannel _httpChannel;

        ChannelRequest(HttpChannel httpChannel, MetaData.Request metaData)
        {
            _httpChannel = Objects.requireNonNull(httpChannel);
            _id = httpChannel.getHttpStream().getId();
            _connectionMetaData = httpChannel.getConnectionMetaData();
            _metaData = Objects.requireNonNull(metaData);
            _response = new ChannelResponse(this);
            _lock = httpChannel._lock;
        }

        HttpStream getStream()
        {
            return getHttpChannel()._stream;
        }

        long getContentBytesRead()
        {
            return getHttpChannel()._contentBytesRead;
        }

        @Override
        public Object getAttribute(String name)
        {
            HttpChannel httpChannel = getHttpChannel();
            if (name.startsWith("org.eclipse.jetty"))
            {
                if (Server.class.getName().equals(name))
                    return httpChannel.getConnectionMetaData().getConnector().getServer();
                if (HttpChannel.class.getName().equals(name))
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
            if (Server.class.getName().equals(name) || HttpChannel.class.getName().equals(name) || HttpConnection.class.getName().equals(name))
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
        public ConnectionMetaData getConnectionMetaData()
        {
            return _connectionMetaData;
        }

        HttpChannel getHttpChannel()
        {
            try (AutoLock ignore = _lock.lock())
            {
                return lockedGetHttpChannel();
            }
        }

        private HttpChannel lockedGetHttpChannel()
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
                HttpChannel httpChannel = lockedGetHttpChannel();

                Content error = httpChannel._error;
                if (error != null)
                    return error;

                if (!httpChannel._processing)
                    return new Content.Error(new IllegalStateException("not processing"));

                stream = httpChannel._stream;
            }

            Content content = stream.readContent();
            if (content != null)
            {
                // TODO: can this be moved to the HttpStream to save re-grabbing the lock to update _bytesRead?
                getHttpChannel()._contentBytesRead += content.remaining();
            }
            return content;
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            boolean error;
            HttpStream stream;
            try (AutoLock ignored = _lock.lock())
            {
                HttpChannel httpChannel = lockedGetHttpChannel();

                error = httpChannel._error != null || !httpChannel._processing;
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
                HttpChannel httpChannel = lockedGetHttpChannel();

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

    static class ChannelResponse implements Response, Callback
    {
        private final ChannelRequest _request;
        private int _status;

        private ChannelResponse(ChannelRequest request)
        {
            _request = request;
        }

        long getContentBytesWritten()
        {
            return _request.getHttpChannel()._contentBytesWritten;
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
                HttpChannel httpChannel = _request.lockedGetHttpChannel();

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
            HttpChannel httpChannel;
            HttpStream stream = null;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();

                if (httpChannel._writeCallback != null)
                    failure = new IllegalStateException("write pending");
                else if (!httpChannel._processing)
                    failure = new IllegalStateException("not processing");
                else if (httpChannel._error != null)
                    failure = httpChannel._error.getCause();
                else if (last && httpChannel._lastWrite)
                    failure = new IllegalStateException("last already written");

                if (failure == null)
                {
                    httpChannel._writeCallback = callback;
                    for (ByteBuffer b : content)
                        httpChannel._contentBytesWritten += b.remaining();

                    httpChannel._lastWrite |= last;

                    stream = httpChannel._stream;
                    written = httpChannel._contentBytesWritten;

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
            HttpChannel httpChannel;
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
            HttpChannel httpChannel;
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
        public void reset()
        {
            _request.getHttpChannel().reset();
        }

        private MetaData.Response lockedPrepareResponse(HttpChannel httpChannel, boolean last)
        {
            // Assume 200 unless told otherwise.
            if (_status == 0)
                _status = HttpStatus.OK_200;

            // Can we set the content length?
            HttpFields.Mutable mutableHeaders = httpChannel._responseHeaders.getMutableHttpFields();
            httpChannel._committedContentLength = mutableHeaders.getLongField(HttpHeader.CONTENT_LENGTH);
            if (last && httpChannel._committedContentLength < 0L)
            {
                httpChannel._committedContentLength = httpChannel._contentBytesWritten;
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

        private ChannelCallback(ChannelRequest request)
        {
            _request = request;
        }

        @Override
        public void succeeded()
        {
            // Called when the request/response cycle is completing successfully.
            HttpStream stream;
            boolean nothingToWrite;
            HttpChannel httpChannel;
            Throwable failure = null;
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();

                if (!httpChannel._processing)
                    throw new IllegalStateException("not processing");

                // We are being tough on handler implementations and expect them
                // to not have pending operations when calling succeeded or failed.
                if (httpChannel._onContentAvailable != null)
                    throw new IllegalStateException("demand pending");
                if (httpChannel._writeCallback != null)
                    throw new IllegalStateException("write pending");
                Content.Error error = httpChannel._error;
                if (error != null)
                    throw new IllegalStateException("error " + error, error.getCause());

                stream = httpChannel._stream;

                long written = httpChannel._contentBytesWritten;
                if (httpChannel._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannel, true);

                long committedContentLength = httpChannel._committedContentLength;
                if (committedContentLength >= 0 && committedContentLength != written)
                    failure = new IOException("content-length %d != %d written".formatted(committedContentLength, written));

                nothingToWrite = failure != null || httpChannel._lastWrite;

                if (nothingToWrite)
                    httpChannel.lockedRecycle();
            }

            // is the request fully consumed?
            Throwable unconsumed = stream.consumeAll();
            if (LOG.isDebugEnabled())
                LOG.debug("consumeAll: {} {} ", unconsumed == null, httpChannel);

            if (unconsumed != null && httpChannel.getConnectionMetaData().isPersistent())
            {
                // TODO: add existing failure as suppressed?
                failure = unconsumed;
            }

            if (nothingToWrite)
            {
                if (failure == null)
                    stream.succeeded();
                else
                    stream.failed(failure);
            }
            else
            {
                stream.send(_request._metaData, responseMetaData, true, Callback.from(this::recycle, stream));
            }
        }

        @Override
        public void failed(Throwable x)
        {
            // Called when the request/response cycle is completing with a failure.
            HttpStream stream;
            boolean committed;
            ChannelRequest request;
            HttpChannel httpChannel;
            try (AutoLock ignored = _request._lock.lock())
            {
                httpChannel = _request.lockedGetHttpChannel();

                if (!httpChannel._processing)
                    throw new IllegalStateException("not processing");

                // Verify whether we can write an error response.
                committed = httpChannel._stream.isCommitted();
                stream = httpChannel._stream;
                request = _request;

                if (committed)
                {
                    httpChannel.lockedRecycle();
                }
                else
                {
                    _request._response._status = HttpStatus.INTERNAL_SERVER_ERROR_500;
                    httpChannel._responseHeaders.reset();
                    // Cannot recycle just yet, since we need to generate the error response.
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("failed {}", httpChannel, x);

            // Consume any input.
            Throwable unconsumed = stream.consumeAll();
            if (unconsumed != null)
                x.addSuppressed(unconsumed);

            if (committed)
            {
                stream.failed(x);
            }
            else
            {
                ErrorResponse response = new ErrorResponse(request, stream, x);
                Response.writeError(request, response, Callback.from(response, this::recycle), x);
            }
        }

        private void recycle()
        {
            try (AutoLock ignored = _request._lock.lock())
            {
                HttpChannel httpChannel = _request.lockedGetHttpChannel();
                httpChannel.lockedRecycle();
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            // TODO review this as it is probably not correct
            return _request.getStream().getInvocationType();
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
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            MetaData.Response responseMetaData = null;
            try (AutoLock ignored = _request._lock.lock())
            {
                HttpChannel httpChannel = _request.lockedGetHttpChannel();

                httpChannel._writeCallback = callback;
                for (ByteBuffer b : content)
                    httpChannel._contentBytesWritten += b.remaining();

                httpChannel._lastWrite |= last;

                if (httpChannel._responseHeaders.commit())
                    responseMetaData = _request._response.lockedPrepareResponse(httpChannel, last);
            }
            _stream.send(_request._metaData, responseMetaData, last, callback, content);
        }

        @Override
        public void succeeded()
        {
            // ErrorProcessor has succeeded the ErrorRequest, so we need to ensure
            // that the ErrorResponse is committed before we fail the stream.
            if (_stream.isCommitted())
                _stream.failed(_failure);
            else
                write(true, Callback.from(() -> _stream.failed(_failure), this::failed));
        }

        @Override
        public void failed(Throwable x)
        {
            if (x != _failure)
                _failure.addSuppressed(x);
            _stream.failed(_failure);
        }
    }
}
