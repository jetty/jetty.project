//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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
public class HttpChannel extends Attributes.Lazy
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);

    public static final String UPGRADE_CONNECTION_ATTRIBUTE = HttpChannel.class.getName() + ".UPGRADE";
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
    private final AutoLock _lock = new AutoLock();
    private final Runnable _handle = new RunHandle();
    private final Server _server;
    private final ConnectionMetaData _connectionMetaData;
    private final HttpConfiguration _configuration;
    private final SerializedInvoker _serializedInvoker;
    private final Attributes _requestAttributes = new Attributes.Lazy();
    private int _requests;

    private HttpStream _stream;
    private ChannelRequest _request;
    private Consumer<Throwable> _onConnectionComplete;

    public HttpChannel(Server server, ConnectionMetaData connectionMetaData, HttpConfiguration configuration)
    {
        _server = server;
        _connectionMetaData = connectionMetaData;
        _configuration = Objects.requireNonNull(configuration);
        // The SerializedInvoker is used to prevent infinite recursion of callbacks calling methods calling callbacks etc.
        _serializedInvoker = new SerializedInvoker()
        {
            @Override
            protected void onError(Runnable task, Throwable t)
            {
                Content.Error error;
                try (AutoLock ignore = _lock.lock())
                {
                    error = _request == null ? null : _request._error;
                }

                if (error != null)
                {
                    // We are already in error, so we will not handle this one
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

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public void setStream(HttpStream stream)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_stream != null)
                throw new IllegalStateException("Stream pending");
            _stream = stream;
        }
    }

    public HttpStream getHttpStream()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request.getStream();
        }
    }

    public Server getServer()
    {
        return _server;
    }

    public ConnectionMetaData getConnectionMetaData()
    {
        return _connectionMetaData;
    }

    public Connection getConnection()
    {
        return _connectionMetaData.getConnection();
    }

    public Connector getConnector()
    {
        return _connectionMetaData.getConnector();
    }

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
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest {} {}", request, this);

            if (_request != null)
                throw new IllegalStateException();

            _request = new ChannelRequest(request);

            if (!HttpMethod.CONNECT.is(request.getMethod()) && !_request.getPath().startsWith("/") && !HttpMethod.OPTIONS.is(request.getMethod()))
                throw new BadMessageException("Bad URI path");

            HttpURI uri = request.getURI();
            if (uri.hasViolations())
            {
                String badMessage = UriCompliance.checkUriCompliance(_configuration.getUriCompliance(), uri);
                if (badMessage != null)
                    throw new BadMessageException(badMessage);
            }

            // This is deliberately not serialized as to allow a handler to block.
            return _handle;
        }
    }

    protected Request getRequest()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request;
        }
    }

    protected Response getResponse()
    {
        try (AutoLock ignored = _lock.lock())
        {
            return _request == null ? null : _request._response;
        }
    }

    public Runnable onContentAvailable()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_request == null)
                return null;
            Runnable onContent = _request._onContentAvailable;
            _request._onContentAvailable = null;
            return _serializedInvoker.offer(onContent);
        }
    }

    public Invocable.InvocationType getOnContentAvailableInvocationType()
    {
        // TODO Can this actually be done, as we may need to invoke other Runnables after onContent?
        try (AutoLock ignored = _lock.lock())
        {
            return Invocable.getInvocationType(_request == null ? null : _request._onContentAvailable);
        }
    }

    public Runnable onError(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onError {}", this, x);
        try (AutoLock ignored = _lock.lock())
        {
            // If the channel doesn't have a stream, then the error is ignored
            if (_stream == null)
                return null;

            // If the channel doesn't have a request, then the error must have occurred during parsing the request header
            // Make a temp request for logging and producing 400 response.
            ChannelRequest request = _request == null ? _request = new ChannelRequest(ERROR_REQUEST) : _request;

            // Remember the error and arrange for any subsequent reads, demands or writes to fail with this error
            if (request._error == null)
                request._error = new Content.Error(x);
            else if (request._error.getCause() != x)
            {
                request._error.getCause().addSuppressed(x);
                return null;
            }

            // invoke onDataAvailable if we are currently demanding
            Runnable invokeOnContentAvailable = request._onContentAvailable;
            request._onContentAvailable = null;

            // if a write is in progress, break the linkage and fail the callback
            Callback onWriteComplete = request._response._onWriteComplete;
            request._response._onWriteComplete = null;
            Runnable invokeWriteFailure = onWriteComplete == null ? null : () -> onWriteComplete.failed(x);

            // Invoke any onError listener(s);
            Consumer<Throwable> onError = request._onError;
            request._onError = null;
            Runnable invokeOnError = onError == null ? null : () -> onError.accept(x);

            // Serialize all the error actions.
            request._processing = true;
            return _serializedInvoker.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnError, () -> request._callback.failed(x));
        }
    }

    public Runnable onConnectionClose(Throwable failed)
    {
        boolean hasStream;
        Consumer<Throwable> onConnectionClose;
        try (AutoLock ignored = _lock.lock())
        {
            hasStream = _stream != null;
            onConnectionClose = _onConnectionComplete;
            _onConnectionComplete = null;
        }

        Runnable runStreamOnError = hasStream && failed != null ? () -> _serializedInvoker.run(() -> onError(failed)) : null;
        Runnable runOnConnectionClose = onConnectionClose == null ? null : () -> onConnectionClose.accept(failed);
        return _serializedInvoker.offer(runStreamOnError, runOnConnectionClose);
    }

    public void addStreamWrapper(Function<HttpStream, HttpStream.Wrapper> onStreamEvent)
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

    public void addConnectionCloseListener(Consumer<Throwable> onClose)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (_onConnectionComplete == null)
                _onConnectionComplete = onClose;
            else
            {
                Consumer<Throwable> previous = _onConnectionComplete;
                _onConnectionComplete = (failed) ->
                {
                    notifyConnectionClose(previous, failed);
                    notifyConnectionClose(onClose, failed);
                };
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

    private void notifyConnectionClose(Consumer<Throwable> onConnectionComplete, Throwable failed)
    {
        if (onConnectionComplete != null)
        {
            try
            {
                onConnectionComplete.accept(failed);
            }
            catch (Throwable t)
            {
                LOG.warn("onConnectionComplete.accept", t);
            }
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s}", this.getClass().getSimpleName(), hashCode(), _request);
    }

    private class RunHandle implements Invocable.Task
    {
        @Override
        public void run()
        {
            _server.customizeHandleAndProcess(_request, _request._response, _request._callback);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _server.getInvocationType();
        }
    }

    class ChannelRequest implements Attributes, Request
    {
        final MetaData.Request _metaData;
        final String _id;
        final ChannelResponse _response;
        Content.Error _error;
        Consumer<Throwable> _onError;
        Runnable _onContentAvailable;
        boolean _processing;
        private final Callback _callback = new RequestCallback();

        ChannelRequest(MetaData.Request metaData)
        {
            if (metaData == null)
                new Throwable().printStackTrace();
            Objects.requireNonNull(metaData);
            _requests++;
            _requestAttributes.clearAttributes();
            _id = Integer.toString(_requests);
            _metaData = metaData;
            _response = new ChannelResponse(this);
        }

        @Override
        public boolean isComplete()
        {
            try (AutoLock ignored = _lock.lock())
            {
                return _stream == null || _stream.isComplete();
            }
        }

        HttpStream getStream()
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_stream == null)
                    throw new IllegalStateException();
                return _stream;
            }
        }

        @Override
        public Object getAttribute(String name)
        {
            if (name.startsWith("org.eclipse.jetty"))
            {
                if (Server.class.getName().equals(name))
                    return getServer();
                if (HttpChannel.class.getName().equals(name))
                    return HttpChannel.this;
                if (HttpConnection.class.getName().equals(name) &&
                    getConnectionMetaData().getConnection() instanceof HttpConnection)
                    return getConnectionMetaData().getConnection();
            }
            return _requestAttributes.getAttribute(name);
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _requestAttributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            if (Server.class.getName().equals(name) || HttpChannel.class.getName().equals(name) || HttpConnection.class.getName().equals(name))
                return null;
            return _requestAttributes.setAttribute(name, attribute);
        }

        @Override
        public Set<String> getAttributeNamesSet()
        {
            return _requestAttributes.getAttributeNamesSet();
        }

        @Override
        public void clearAttributes()
        {
            _requestAttributes.clearAttributes();
        }

        @Override
        public void execute(Runnable task)
        {
            _server.getThreadPool().execute(task);
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

        @Override
        public HttpChannel getHttpChannel()
        {
            return HttpChannel.this;
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
        public String getPath()
        {
            return _metaData.getURI().getDecodedPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _metaData.getFields();
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
            try (AutoLock ignored = _lock.lock())
            {
                if (_error != null)
                    return _error;
                if (!_processing)
                    return new Content.Error(new IllegalStateException("not processing"));
            }
            return getStream().readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            boolean error;
            try (AutoLock ignored = _lock.lock())
            {
                error = _error != null || !_processing;
                if (!error)
                {
                    if (_onContentAvailable != null)
                        throw new IllegalArgumentException("Demand pending");
                    _onContentAvailable = onContentAvailable;
                }
            }

            if (error)
                _serializedInvoker.run(onContentAvailable);
            else
                getStream().demandContent();
        }

        @Override
        public void addErrorListener(Consumer<Throwable> onError)
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_error != null)
                {
                    _serializedInvoker.run(() -> onError.accept(_error.getCause()));
                    return;
                }

                if (_onError == null)
                    _onError = onError;
                else
                {
                    Consumer<Throwable> previous = _onError;
                    _onError = throwable ->
                    {
                        try
                        {
                            previous.accept(throwable);
                        }
                        finally
                        {
                            onError.accept(throwable);
                        }
                    };
                }
            }
        }

        @Override
        public void addCompletionListener(Callback onComplete)
        {
            addStreamWrapper(s -> new HttpStream.Wrapper(s)
            {
                @Override
                public void succeeded()
                {
                    try
                    {
                        onComplete.succeeded();
                    }
                    finally
                    {
                        super.succeeded();
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    try
                    {
                        onComplete.failed(x);
                    }
                    finally
                    {
                        super.failed(x);
                    }
                }
            });
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

        private class RequestCallback implements Callback
        {
            @Override
            public void succeeded()
            {
                // Called when the request/response cycle is completing successfully.
                HttpStream stream;
                MetaData.Response commit;
                long contentLength;
                long written;
                try (AutoLock ignored = _lock.lock())
                {
                    if (!_processing)
                        throw new IllegalStateException("not processing");

                    // We are being tough on handler implementations and expect them to not have pending operations
                    // when calling succeeded or failed
                    if (_onContentAvailable != null)
                        throw new IllegalStateException("onContentAvailable Pending");
                    if (_response._onWriteComplete != null)
                        throw new IllegalStateException("write pending");
                    if (_error != null)
                        throw new IllegalStateException("error " + _error, _error.getCause());

                    if (_stream == null | _request != ChannelRequest.this)
                        return;
                    stream = _stream;
                    _stream = null;
                    _request = null;

                    commit = _response.commitResponse(true);
                    contentLength = _response._contentLength;
                    written = _response._written;
                }

                // is the request fully consumed?
                Throwable unconsumed = stream.consumeAll();
                if (LOG.isDebugEnabled())
                    LOG.debug("consumeAll {} ", this, unconsumed);
                if (unconsumed != null && getConnectionMetaData().isPersistent())
                    stream.failed(unconsumed);
                else if (contentLength >= 0L && contentLength != written)
                    stream.failed(new IOException(String.format("contentLength %d != %d", contentLength, written)));
                else
                    stream.send(commit, true, stream);
            }

            @Override
            public void failed(Throwable x)
            {
                // Called when the request/response cycle is completing with a failure.
                HttpStream stream;
                boolean committed;
                ChannelRequest request;
                try (AutoLock ignored = _lock.lock())
                {
                    if (_stream == null || _request != ChannelRequest.this)
                        return;

                    if (!_processing)
                        throw new IllegalStateException("not processing");

                    // Can we write out an error response
                    committed = _stream.isCommitted();
                    stream = _stream;
                    _stream = null;
                    request = _request;
                    _request = null;

                    // reset response;
                    if (!committed)
                    {
                        _response._status = HttpStatus.INTERNAL_SERVER_ERROR_500;
                        _response._headers.recycle();
                    }

                    // Cancel any callbacks
                    _onError = null;
                    _response._onWriteComplete = null;
                    _onContentAvailable = null;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("failed {}", stream, x);

                if (committed)
                    stream.failed(x);
                else
                {
                    Response response = new ErrorResponse(request, stream);
                    response.writeError(request, x, Callback.from(
                        () ->
                        {
                            // ErrorHandler has succeeded the ErrorRequest, so we need to ensure
                            // the ErrorResponse is committed before we fail the stream.
                            if (stream.isCommitted())
                                stream.failed(x);
                            else
                            {
                                response.write(true, Callback.from(() -> stream.failed(x), t ->
                                {
                                    if (t != x)
                                        x.addSuppressed(t);
                                    stream.failed(x);
                                }));
                            }
                        },
                        t ->
                        {
                            if (t != x)
                                x.addSuppressed(t);
                            stream.failed(x);
                        }
                    ));
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                // TODO review this as it is probably not correct
                return getStream().getInvocationType();
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x %s %s", getMethod(), hashCode(), getHttpURI(), _metaData.getHttpVersion());
        }
    }

    private class ChannelResponse implements Response, Callback
    {
        private final ChannelRequest _request;
        private final ResponseHttpFields _headers = new ResponseHttpFields();
        private int _status;
        private ResponseHttpFields _trailers;
        private Callback _onWriteComplete;
        private long _written;
        private long _contentLength = -1L;

        private ChannelResponse(ChannelRequest request)
        {
            _request = request;
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
            return _headers;
        }

        @Override
        public HttpFields.Mutable getTrailers()
        {
            try (AutoLock ignored = _lock.lock())
            {
                // TODO check if trailers allowed in version and transport?
                if (_trailers == null)
                    _trailers = new ResponseHttpFields();
                return _trailers;
            }
        }

        private HttpFields takeTrailers()
        {
            try (AutoLock ignored = _lock.lock())
            {
                ResponseHttpFields trailers = _trailers;
                if (trailers != null)
                    trailers.toReadOnly();
                return trailers;
            }
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            MetaData.Response commit = null;
            HttpStream stream = null;
            long contentLength = -1;
            long written = -1;
            Throwable failure = null;
            try (AutoLock ignored = _lock.lock())
            {
                if (_onWriteComplete != null)
                    failure = new IllegalStateException("write pending");
                else if (!_request._processing)
                    failure = new IllegalStateException("not processing");
                else if (_request._error != null)
                    failure = _request._error.getCause();
                if (_stream == null)
                    failure = new IllegalStateException("completed");
                else
                {
                    _onWriteComplete = callback;
                    for (ByteBuffer b : content)
                    {
                        _written += b.remaining();
                    }

                    commit = commitResponse(last);
                    stream = _stream;
                    contentLength = _contentLength;
                    written = _written;
                }
            }

            // If the content lengths were not compatible with what was written, then we need to abort
            if (contentLength >= 0)
            {
                String lengthError = (contentLength < written) ? "content-length %d < %d"
                    : (last && contentLength > written) ? "content-length %d > %d" : null;
                if (lengthError != null)
                {
                    String message = String.format(lengthError, contentLength, written);
                    if (LOG.isDebugEnabled())
                        LOG.debug("fail {} {}", callback, message);
                    failure = new IOException(message);
                }
            }

            if (failure != null)
            {
                Throwable t = failure;
                _serializedInvoker.run(() -> callback.failed(t));
                return;
            }

            // Do the write
            stream.send(commit, last, this, content);
        }

        @Override
        public void succeeded()
        {
            // Called when an individual write succeeds
            Callback callback;
            try (AutoLock ignored = _lock.lock())
            {
                callback = _onWriteComplete;
                _onWriteComplete = null;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("write succeeded {}", callback);
            if (callback != null)
                callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            // Called when an individual write fails
            Callback callback;
            try (AutoLock ignored = _lock.lock())
            {
                callback = _onWriteComplete;
                _onWriteComplete = null;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("write failed {}", callback, x);
            if (callback != null)
                callback.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            try (AutoLock ignored = _lock.lock())
            {
                return Invocable.getInvocationType(_onWriteComplete);
            }
        }

        @Override
        public void push(MetaData.Request request)
        {
            _request.getStream().push(request);
        }

        @Override
        public boolean isCommitted()
        {
            return _headers.isReadOnly();
        }

        @Override
        public void reset()
        {
            try (AutoLock ignored = _lock.lock())
            {
                if (_headers.isReadOnly())
                    throw new IllegalStateException("Committed");

                _headers.clear(); // TODO re-add or don't delete default fields
                if (_trailers != null)
                    _trailers.clear();
                _status = 0;
            }
        }

        private MetaData.Response commitResponse(boolean last)
        {
            if (!_lock.isHeldByCurrentThread())
                throw new IllegalStateException();

            // Are we already committed?
            if (_headers.isReadOnly())
                return null;

            // Assume 200 unless told otherwise
            if (_status == 0)
                _status = HttpStatus.OK_200;

            // Can we set the content length?
            _contentLength = _headers.getLongField(HttpHeader.CONTENT_LENGTH);
            if (last && _contentLength < 0L)
            {
                _contentLength = _written;
                if (_contentLength == 0)
                    _headers.put(CONTENT_LENGTH_0);
                else
                    _headers.putLongField(HttpHeader.CONTENT_LENGTH, _contentLength);
            }

            // Add the date header
            if (_configuration.getSendDateHeader() && !_headers.contains(HttpHeader.DATE))
                _headers.put(getServer().getDateField());

            // Freeze the headers and mark this response as committed
            _headers.toReadOnly();

            // Provide trailers if they exist
            Supplier<HttpFields> trailers = _trailers == null ? null : this::takeTrailers;

            return new MetaData.Response(
                getConnectionMetaData().getVersion(),
                _status,
                null,
                _headers,
                -1,
                trailers);
        }
    }

    private class ErrorResponse extends Response.Wrapper
    {
        private final ChannelRequest _request;
        private final HttpStream _stream;

        public ErrorResponse(ChannelRequest request, HttpStream stream)
        {
            super(request._response);
            _request = request;
            _stream = stream;
        }

        @Override
        public boolean isCommitted()
        {
            return false;
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
            MetaData.Response commit;
            try (AutoLock ignored = _lock.lock())
            {
                for (ByteBuffer b : content)
                    _request._response._written += b.remaining();
                commit = _request._response.commitResponse(last);
            }

            // Do the write
            _stream.send(commit, last, callback, content);
        }
    }
}
