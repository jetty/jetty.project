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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedExecutor;
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
public class HttpChannel extends AttributesMap
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

    private final AutoLock _lock = new AutoLock();
    private final Runnable _handle = new RunHandle();
    private final Server _server;
    private final ConnectionMetaData _connectionMetaData;
    private final HttpConfiguration _configuration;
    private final SerializedExecutor _serializedExecutor;
    private int _requests;

    private HttpStream _stream;
    private ChannelRequest _request;
    private Consumer<Throwable> _onConnectionComplete;

    public HttpChannel(Server server, ConnectionMetaData connectionMetaData, HttpConfiguration configuration)
    {
        _server = server;
        _connectionMetaData = connectionMetaData;
        _configuration = configuration;
        _serializedExecutor = new SerializedExecutor(_server.getThreadPool())
        {
            @Override
            protected void onError(Runnable task, Throwable t)
            {
                Content.Error error;
                try (AutoLock ignore = _lock.lock())
                {
                    error = _request == null ? null : _request._error;
                }
                if (error != null && error.getCause() != null)
                {
                    if (error.getCause() != t)
                        error.getCause().addSuppressed(t);
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

    public HttpStream getStream()
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

    public ConnectionMetaData getMetaConnection()
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

    /**
     * Start request handling by returning a Runnable that will call {@link Server#handle(Request, Response)}.
     * @param request The request metadata to handle.
     * @return A Runnable that will call {@link Server#handle(Request, Response)}.  Unlike all other Runnables
     * returned by {@link HttpChannel} methods, this runnable is not mutually excluded or serialized against the other
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

            if (!_request.getPath().startsWith("/") && !HttpMethod.OPTIONS.is(request.getMethod()) && !HttpMethod.CONNECT.is(request.getMethod()))
                throw new BadMessageException();

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
            return _serializedExecutor.offer(onContent);
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

    public boolean onIdleTimeout(long now, long timeoutNanos)
    {
        // TODO check time against last activity and return true only if we really are idle.
        //      If we return true, then onError will be called with a real exception... or is that too late????
        return true;
    }

    public Runnable onError(Throwable x)
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onError {} {}", this, x);

            // If the channel doesn't have a stream, then the error is ignored
            if (_stream == null)
                return null;

            // If the channel doesn't have a request, then the error must have occurred during parsing the request header
            // Make a temp request for logging and producing 400 response.
            ChannelRequest request = _request == null ? _request = new ChannelRequest(null) : _request;

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
            return _serializedExecutor.offer(invokeOnContentAvailable, invokeWriteFailure, invokeOnError, () -> request.failed(x));
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

        return _serializedExecutor.offer(
            hasStream ? () -> _serializedExecutor.execute(() -> onError(failed)) : null,
            onConnectionClose == null ? null : () -> onConnectionClose.accept(failed));
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

    /** Format the address or host returned from Request methods
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
                t.printStackTrace();
            }
        }
    }

    private class RunHandle implements Runnable
    {
        @Override
        public void run()
        {
            if (!_server.handle(_request, _request._response))
                throw new IllegalStateException();
        }
    }

    private class ChannelRequest extends AttributesMap implements Request
    {
        final MetaData.Request _metaData;
        final String _id;
        final ChannelResponse _response;
        Content.Error _error;
        Consumer<Throwable> _onError;
        Runnable _onContentAvailable;

        private Request _wrapper = this;

        ChannelRequest(MetaData.Request metaData)
        {
            _requests++;
            _id = Integer.toString(_requests);
            _metaData = metaData;
            _response = new ChannelResponse(this);
        }

        @Override
        public Response getResponse()
        {
            return _response;
        }

        @Override
        public void setWrapper(Request wrapper)
        {
            if (wrapper.getWrapped() != _wrapper)
                throw new IllegalStateException("B B B Bad rapping!");
            _wrapper = wrapper;
        }

        @Override
        public Request getWrapper()
        {
            return _wrapper;
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
            return super.getAttribute(name);
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
        public HttpChannel getChannel()
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
            }
            return getStream().readContent();
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            boolean error;
            try (AutoLock ignored = _lock.lock())
            {
                error = _error != null;
                if (!error)
                {
                    if (_onContentAvailable != null && _onContentAvailable != onContentAvailable)
                        throw new IllegalArgumentException("Demand pending");
                    _onContentAvailable = onContentAvailable;
                }
            }

            if (error)
                _serializedExecutor.execute(onContentAvailable);
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
                    _serializedExecutor.execute(() -> onError.accept(_error.getCause()));
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
                // We are being tough on handler implementations and expect them to not have pending operations
                // when calling succeeded or failed
                if (_onContentAvailable != null)
                    throw new IllegalStateException("onContentAvailable Pending");
                if (_response._onWriteComplete != null)
                    throw new IllegalStateException("write pending");
                if (_error != null)
                    throw new IllegalStateException("error " + _error);

                if (_stream == null | _request != this)
                    return;
                stream = _stream;
                _stream = null;
                _request = null;

                commit = _response.commitResponse(true);
                contentLength = _response._contentLength;
                written = _response._written;
            }

            // ensure the request is consumed
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
            // This is equivalent to the previous HttpTransport.abort(Throwable), so we don't need to do much clean up
            // as channel will be shutdown and thrown away.
            HttpStream stream;
            MetaData.Response commit = null;
            ByteBuffer content = BufferUtil.EMPTY_BUFFER;
            try (AutoLock ignored = _lock.lock())
            {
                if (_stream == null || _request != this)
                    return;

                // Can we write out an error response
                if (!_stream.isCommitted())
                {
                    int status = HttpStatus.INTERNAL_SERVER_ERROR_500;
                    String reason = x.toString();
                    if (x instanceof BadMessageException)
                    {
                        BadMessageException bme = (BadMessageException)x;
                        status = bme.getCode();
                        reason = bme.getReason();
                    }
                    if (reason == null)
                        reason = HttpStatus.getMessage(status);

                    _response._headers.recycle();
                    _response._status = status;

                    if (!HttpStatus.hasNoBody(_response._status))
                    {
                        _response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_8859_1.asString());
                        content = BufferUtil.toBuffer("<h1>Bad Message " + _response._status + "</h1>\n<pre>reason: " + reason + "</pre>");
                        _response._written = content.remaining();
                        commit = _response.commitResponse(true);
                    }
                }
                stream = _stream;
                _stream = null;
                _request = null;

                // Cancel any callbacks
                _onError = null;
                _response._onWriteComplete = null;
                _onContentAvailable = null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("failed {} {}", stream, x);

            // committed, but the stream is still able to send a response
            if (commit == null)
                stream.failed(x);
            else
            {
                stream.send(commit,
                    true,
                    Callback.from(
                        () -> stream.failed(x),
                        t ->
                        {
                            if (t != x)
                                x.addSuppressed(t);
                            stream.failed(x);
                        }
                    ), content);
            }
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getStream().getInvocationType();
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
        private Response _wrapper = this;

        private ChannelResponse(ChannelRequest request)
        {
            _request = request;
        }

        @Override
        public Request getRequest()
        {
            return _request.getWrapper();
        }

        @Override
        public Response getWrapper()
        {
            return _wrapper;
        }

        @Override
        public void setWrapper(Response wrapper)
        {
            if (wrapper.getWrapped() != _wrapper)
                throw new IllegalStateException("Bbb b bad rapping!");
            _wrapper = wrapper;
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
            MetaData.Response commit;
            long contentLength;
            long written;
            try (AutoLock ignored = _lock.lock())
            {
                if (_onWriteComplete != null)
                    throw new IllegalStateException("Write pending");

                if (_request._error != null)
                {
                    _serializedExecutor.execute(() -> callback.failed(_request._error.getCause()));
                    return;
                }

                _onWriteComplete = callback;
                for (ByteBuffer b : content)
                    _written += b.remaining();

                commit = commitResponse(last);
                contentLength = _contentLength;
                written = _written;
            }

            // If the content lengths were not compatible with what was written, then we need to abort
            if (contentLength >= 0)
            {
                if (contentLength < written)
                {
                    fail(callback, "content-length %d < %d", contentLength, written);
                    return;
                }
                if (last && contentLength > written)
                {
                    fail(callback, "content-length %d > %d", contentLength, written);
                    return;
                }
            }

            // Do the write
            _request.getStream().send(commit, last, this, content);
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

        private void fail(Callback callback, String reason, Object... args)
        {
            String message = String.format(reason, args);
            if (LOG.isDebugEnabled())
                LOG.debug("fail {} {}", callback, message);
            IOException failure = new IOException(message);
            if (callback != null)
                callback.failed(failure);
            if (!getRequest().isComplete())
                getRequest().failed(failure);
        }

        @Override
        public void push(MetaData.Request request)
        {
            _request.getStream().push(request);
        }

        @Override
        public boolean isCommitted()
        {
            try (AutoLock ignored = _lock.lock())
            {
                return _headers.isReadOnly();
            }
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
                getMetaConnection().getVersion(),
                _status,
                null,
                _headers,
                -1,
                trailers);
        }
    }
}
