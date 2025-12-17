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

package org.eclipse.jetty.http2.server.internal;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http2.CloseState;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP2 implements HttpStream, HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP2.class);

    private final AutoLock lock = new AutoLock();
    private final HTTP2ServerConnection _connection;
    private final HttpChannel _httpChannel;
    private final AtomicReference<RecycleState> _recycle = new AtomicReference<>(RecycleState.CAN_RECYCLE);
    private final HTTP2Stream _stream;
    private MetaData.Request _requestMetaData;
    private MetaData.Response _responseMetaData;
    private TunnelSupport tunnelSupport;
    private Content.Chunk _chunk;
    private Content.Chunk _trailer;
    private boolean committed;
    private boolean _demand;

    /**
     * This state machine tracks if the {@link #_httpChannel} can be recycled or not.
     * The on*() methods race against succeeded() and they need to figure out if they
     * can use the _httpChannel or if it has been recycled, while succeeded() needs to
     * know if it can safely recycle the _httpChannel because no on*() method was
     * called, or if it cannot.
     */
    private enum RecycleState
    {
        /**
         * start state
         */
        CAN_RECYCLE,

        /**
         * terminal state
         */
        RECYCLED,

        /**
         * terminal state
         */
        CANNOT_RECYCLE;

        /**
         * Change the current state if it isn't a terminal state.
         * @param reference the {@code AtomicReference} containing the state.
         * @param newStateIfCanRecycle the state to change to if the current state is {@link #CAN_RECYCLE}.
         * @return the previous state.
         */
        private static RecycleState changeState(AtomicReference<RecycleState> reference, RecycleState newStateIfCanRecycle)
        {
            assert newStateIfCanRecycle != CAN_RECYCLE;
            return reference.getAndUpdate(currentState -> switch (currentState)
            {
                case CAN_RECYCLE -> newStateIfCanRecycle;
                case RECYCLED -> RECYCLED;
                case CANNOT_RECYCLE -> CANNOT_RECYCLE;
            });
        }
    }

    public HttpStreamOverHTTP2(HTTP2ServerConnection connection, HttpChannel httpChannel, HTTP2Stream stream)
    {
        _connection = connection;
        _httpChannel = httpChannel;
        _stream = stream;
    }

    @Override
    public String getId()
    {
        return String.valueOf(_stream.getId());
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            _requestMetaData = (MetaData.Request)frame.getMetaData();

            // Grab freshly initialized ComplianceViolation.Listener here, no need to reinitialize.
            ComplianceViolation.Listener listener = _httpChannel.getComplianceViolationListener();
            Runnable handler = _httpChannel.onRequest(_requestMetaData);
            Request request = _httpChannel.getRequest();
            listener.onRequestBegin(request);
            // Note UriCompliance is done by HandlerInvoker
            HttpCompliance httpCompliance = _httpChannel.getConnectionMetaData().getHttpConfiguration().getHttpCompliance();
            HttpCompliance.checkHttpCompliance(_requestMetaData, httpCompliance, listener);

            if (frame.isEndStream())
            {
                try (AutoLock ignored = lock.lock())
                {
                    _chunk = Content.Chunk.EOF;
                }
            }

            HttpFields fields = _requestMetaData.getHttpFields();

            if (_requestMetaData instanceof MetaData.ConnectRequest)
                tunnelSupport = new TunnelSupportOverHTTP2(_requestMetaData.getProtocol());

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP2 request #{}/{}, {} {} {}{}{}",
                    _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                    _requestMetaData.getMethod(), _requestMetaData.getHttpURI(), _requestMetaData.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            HttpField expectField = fields.getField(HttpHeader.EXPECT);
            if (expectField != null && !HttpHeaderValue.CONTINUE.is(expectField.getValue()))
                throw new BadMessageException(HttpStatus.EXPECTATION_FAILED_417);

            InvocationType invocationType = Invocable.getInvocationType(handler);
            return new ReadyTask(invocationType, handler)
            {
                @Override
                public void run()
                {
                    if (_stream.isClosed())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("HTTP2 request #{}/{} skipped handling, stream already closed {}",
                                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                                _stream);
                    }
                    else
                    {
                        super.run();
                    }
                }
            };
        }
        catch (Throwable x)
        {
            HttpException httpException = x instanceof HttpException http ? http : new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, x);
            return onBadMessage(httpException);
        }
    }

    private Runnable onBadMessage(HttpException x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("badMessage {} {}", this, x);

        RecycleState previousState = RecycleState.changeState(_recycle, RecycleState.CANNOT_RECYCLE);
        if (previousState == RecycleState.RECYCLED)
            return null;
        Throwable failure = (Throwable)x;
        return _httpChannel.onFailure(failure);
    }

    @Override
    public Content.Chunk read()
    {
        // Tunnel requests do not have HTTP content, avoid
        // returning chunks meant for a different protocol.
        if (tunnelSupport != null)
            return null;

        // Check if there already is a chunk, e.g. EOF.
        Content.Chunk chunk;
        try (AutoLock ignored = lock.lock())
        {
            chunk = _chunk;
            _chunk = Content.Chunk.next(chunk);
        }
        if (chunk != null)
            return chunk;

        Stream.Data data = _stream.readData();
        if (data == null)
            return null;

        // Check if the trailers must be returned.
        if (data.frame().isEndStream())
        {
            Content.Chunk trailer;
            try (AutoLock ignored = lock.lock())
            {
                trailer = _trailer;
                if (trailer != null)
                {
                    data.release();
                    _chunk = Content.Chunk.next(trailer);
                    return trailer;
                }
            }
        }

        // The data instance should be released after readData() above;
        // the chunk is stored below for later use, so should be retained;
        // the two actions cancel each other, no need to further retain or release.
        chunk = createChunk(data);

        try (AutoLock ignored = lock.lock())
        {
            _chunk = Content.Chunk.next(chunk);
        }
        return chunk;
    }

    @Override
    public void demand()
    {
        boolean notify = false;
        boolean demand = false;
        try (AutoLock ignored = lock.lock())
        {
            if (_chunk != null || _trailer != null)
                notify = true;
            else if (!_demand)
                demand = _demand = true;
        }
        if (notify)
        {
            Runnable task = _httpChannel.onContentAvailable();
            if (task != null)
            {
                // We must dispatch, so an application thread does not 
                // become a producer and then consume another request.
                _connection.offerTask(task, true);
            }
        }
        else if (demand)
        {
            _stream.demand();
        }
    }

    @Override
    public Runnable onDataAvailable()
    {
        try (AutoLock ignored = lock.lock())
        {
            _demand = false;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}: data available",
                _stream.getId(),
                Integer.toHexString(_stream.getSession().hashCode()));
        }

        return _httpChannel.onContentAvailable();
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getHttpFields().asImmutable();
        try (AutoLock ignored = lock.lock())
        {
            _trailer = new Trailers(trailers);
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}, trailer:{}{}",
                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                System.lineSeparator(), trailers);
        }

        return _httpChannel.onContentAvailable();
    }

    private Content.Chunk createChunk(Stream.Data data)
    {
        DataFrame frame = data.frame();
        if (frame.isEndStream() && frame.remaining() == 0)
        {
            data.release();
            return Content.Chunk.EOF;
        }
        return Content.Chunk.asChunk(frame.getByteBuffer(), frame.isEndStream(), data);
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
        // Nothing to do here.
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        ByteBuffer content = byteBuffer != null ? byteBuffer : BufferUtil.EMPTY_BUFFER;
        if (response != null)
            sendHeaders(request, response, content, last, callback);
        else
            sendContent(request, content, last, callback);
    }

    private void sendHeaders(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean last, Callback callback)
    {
        _responseMetaData = response;

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        int streamId = _stream.getId();
        if (HttpStatus.isInterim(response.getStatus()))
        {
            // Must not commit interim responses.

            if (hasContent)
            {
                callback.failed(new IllegalStateException("Interim response cannot have content"));
                return;
            }

            headersFrame = new HeadersFrame(streamId, response, null, false);
        }
        else
        {
            committed = true;
            if (last)
            {
                long realContentLength = BufferUtil.length(content);
                long contentLength = response.getContentLength();
                if (contentLength < 0)
                {
                    _responseMetaData = new MetaData.Response(
                        response.getStatus(), response.getReason(), response.getHttpVersion(),
                        response.getHttpFields(),
                        realContentLength,
                        response.getTrailersSupplier()
                    );
                }
                else if (hasContent && contentLength != realContentLength)
                {
                    callback.failed(new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, String.format("Incorrect Content-Length %d!=%d", contentLength, realContentLength)));
                    return;
                }
            }

            if (hasContent)
            {
                headersFrame = new HeadersFrame(streamId, response, null, false);
                if (last)
                {
                    HttpFields trailers = retrieveTrailers();
                    if (trailers == null)
                    {
                        dataFrame = new DataFrame(streamId, content, true);
                    }
                    else
                    {
                        dataFrame = new DataFrame(streamId, content, false);
                        trailersFrame = new HeadersFrame(streamId, new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                    }
                }
                else
                {
                    dataFrame = new DataFrame(streamId, content, false);
                }
            }
            else
            {
                if (last)
                {
                    if (isTunnel(request, response))
                    {
                        headersFrame = new HeadersFrame(streamId, response, null, false);
                    }
                    else
                    {
                        HttpFields trailers = retrieveTrailers();
                        if (trailers == null)
                        {
                            headersFrame = new HeadersFrame(streamId, response, null, true);
                        }
                        else
                        {
                            headersFrame = new HeadersFrame(streamId, response, null, false);
                            trailersFrame = new HeadersFrame(streamId, new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                        }
                    }
                }
                else
                {
                    headersFrame = new HeadersFrame(streamId, response, null, false);
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}:{}{} {}{}{}",
                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                System.lineSeparator(), HttpVersion.HTTP_2, response.getStatus(),
                System.lineSeparator(), response.getHttpFields());
        }

        _stream.send(new HTTP2Stream.FrameList(headersFrame, dataFrame, trailersFrame), callback);
    }

    private void sendContent(MetaData.Request request, ByteBuffer content, boolean last, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (last && !isTunnel(request, _responseMetaData)))
        {
            if (!hasContent)
                content = BufferUtil.EMPTY_BUFFER;
            if (last)
            {
                HttpFields trailers = retrieveTrailers();
                if (trailers == null)
                {
                    sendDataFrame(content, true, true, callback);
                }
                else
                {
                    if (hasContent)
                    {
                        SendTrailers sendTrailers = new SendTrailers(callback, trailers);
                        sendDataFrame(content, true, false, sendTrailers);
                    }
                    else
                    {
                        sendTrailersFrame(new MetaData(HttpVersion.HTTP_2, trailers), callback);
                    }
                }
            }
            else
            {
                sendDataFrame(content, false, false, callback);
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    @Override
    public Runnable cancelSend(Throwable cause, Callback appCallback)
    {
        // Append a ResetFrame into the H2 flusher and complete the appCallback once all the frames
        // up to and including the reset one have been flushed; it is needed to wait to make sure
        // the completion listeners aren't called while a write is still pending.
        return () ->
        {
            _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            _stream.getSession().flush(Callback.from(() ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("cancelSend reset and flushed");
                appCallback.failed(cause);
            }));
        };
    }

    private HttpFields retrieveTrailers()
    {
        Supplier<HttpFields> supplier = _responseMetaData.getTrailersSupplier();
        if (supplier == null)
            return null;
        HttpFields trailers = supplier.get();
        if (trailers == null)
            return null;
        return trailers.size() == 0 ? null : trailers;
    }

    private boolean isTunnel(MetaData.Request request, MetaData.Response response)
    {
        return MetaData.isTunnel(request.getMethod(), response.getStatus());
    }

    @Override
    public long getIdleTimeout()
    {
        return _stream.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeoutMs)
    {
        _stream.setIdleTimeout(idleTimeoutMs);
    }

    @Override
    public void push(MetaData.Request resource)
    {
        if (!_stream.getSession().isPushEnabled())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP/2 push disabled for {}", resource);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("HTTP/2 push {}", resource);

        _stream.push(new PushPromiseFrame(_stream.getId(), resource), new Promise<>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                _connection.push((HTTP2Stream)pushStream, resource);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.atDebug().setCause(x).log("Could not HTTP/2 push {}", resource);
            }
        }, null); // TODO: handle reset from the client ?
    }

    public Runnable onPushRequest(MetaData.Request request)
    {
        try
        {
            _requestMetaData = request;
            Runnable task = _httpChannel.onRequest(request);
            _httpChannel.getRequest().setAttribute("org.eclipse.jetty.pushed", Boolean.TRUE);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP/2 push request #{}/{}:{}{} {} {}{}{}",
                    _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()), System.lineSeparator(),
                    request.getMethod(), request.getHttpURI(), request.getHttpVersion(),
                    System.lineSeparator(), request.getHttpFields());
            }

            return task;
        }
        catch (Throwable x)
        {
            HttpException httpException = x instanceof HttpException http ? http : new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, x);
            return () -> onBadMessage(httpException);
        }
    }

    private void sendDataFrame(ByteBuffer content, boolean lastContent, boolean endStream, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}: {} content bytes{}",
                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                content.remaining(), lastContent ? " (last chunk)" : "");
        }
        DataFrame frame = new DataFrame(_stream.getId(), content, endStream);
        _stream.data(frame, callback);
    }

    private void sendTrailersFrame(MetaData metaData, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}: trailers",
                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
        }

        HeadersFrame frame = new HeadersFrame(_stream.getId(), metaData, null, true);
        _stream.headers(frame, callback);
    }

    @Override
    public boolean isCommitted()
    {
        return committed;
    }

    @Override
    public boolean isIdle()
    {
        // TODO: is this necessary?
        return false;
    }

    @Override
    public TunnelSupport getTunnelSupport()
    {
        return tunnelSupport;
    }

    @Override
    public Throwable consumeAvailable()
    {
        if (tunnelSupport != null)
            return null;
        Throwable result = HttpStream.consumeAvailable(this, _httpChannel.getConnectionMetaData().getHttpConfiguration());
        if (result != null)
        {
            _trailer = null;
            if (_chunk != null)
                _chunk.release();
            _chunk = Content.Chunk.from(result, true);
        }
        return result;
    }

    @Override
    public void onTimeout(TimeoutException timeout, BiConsumer<Runnable, Boolean> consumer)
    {
        RecycleState previousState = RecycleState.changeState(_recycle, RecycleState.CANNOT_RECYCLE);
        if (previousState == RecycleState.RECYCLED)
            return;
        HttpChannel.IdleTimeoutTask task = _httpChannel.onIdleTimeout(timeout);
        consumer.accept(task.action(), !task.handlingRequest());
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        RecycleState previousState = RecycleState.changeState(_recycle, RecycleState.CANNOT_RECYCLE);
        if (previousState == RecycleState.RECYCLED)
            return new FailureTask(null, callback);
        boolean remote = failure instanceof EOFException;
        Runnable task = remote ? _httpChannel.onRemoteFailure(new EofException(failure)) : _httpChannel.onFailure(failure);
        return new FailureTask(task, callback);
    }

    @Override
    public void succeeded()
    {
        if (!_stream.isClosed())
        {
            if (isTunnel(_requestMetaData, _responseMetaData))
            {
                Connection connection = (Connection)_httpChannel.getRequest().getAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE);
                if (connection == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("HTTP2 response #{}/{}: no upgrade connection, resetting stream", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
                    _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                }
                else
                {
                    EndPoint endPoint = tunnelSupport.getEndPoint();
                    _stream.setAttachment(endPoint);
                    endPoint.upgrade(connection);
                }
            }
            else
            {
                if (HttpMethod.CONNECT.is(_requestMetaData.getMethod()))
                {
                    // It was a tunnel attempt, but it failed with a non-200 response.
                    if (LOG.isDebugEnabled())
                        LOG.debug("HTTP2 response #{}/{}: tunnel failed with {} response", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()), _responseMetaData.getStatus());
                    // Implicitly close the request side of the stream that was left
                    // open by the client to tunnel opaque bytes via DATA frames.
                    // Don't send RST_STREAM if a response was already sent.
                    if (_stream.updateClose(true, CloseState.Event.RECEIVED))
                        _stream.getSession().removeStream(_stream);
                    else
                        _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                }
                else
                {
                    // If the stream is not closed, it is still reading the request content.
                    // Send a reset to the other end so that it stops sending data.
                    if (LOG.isDebugEnabled())
                        LOG.debug("HTTP2 response #{}/{}: unconsumed request content, resetting stream", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
                    _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                }
            }
        }

        RecycleState previousState = RecycleState.changeState(_recycle, RecycleState.RECYCLED);
        if (previousState == RecycleState.CAN_RECYCLE)
        {
            if (_connection.isRecycleHttpChannels())
            {
                _httpChannel.recycle();
                _connection.offerHttpChannel(_httpChannel);
            }
        }
    }

    @Override
    public void failed(Throwable x)
    {
        if (HttpMethod.CONNECT.is(_requestMetaData.getMethod()))
        {
            // It was a tunnel attempt, but it failed.
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("HTTP2 response #{}/{}: tunnel failed", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
            // Implicitly close the request side of the stream that was left
            // open by the client to tunnel opaque bytes via DATA frames.
            // Don't send RST_STREAM if a response was already sent.
            if (_stream.updateClose(true, CloseState.Event.RECEIVED))
                _stream.getSession().removeStream(_stream);
            else
                _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }
        else
        {
            ErrorCode errorCode = x == HttpStream.CONTENT_NOT_CONSUMED ? ErrorCode.NO_ERROR : ErrorCode.CANCEL_STREAM_ERROR;
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(x).log("HTTP2 response #{}/{}: failed {}", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()), errorCode);
            _stream.reset(new ResetFrame(_stream.getId(), errorCode.code), Callback.NOOP);
        }
        // Change the state just to make the content of _recycle less surprising when debugging.
        _recycle.set(RecycleState.CANNOT_RECYCLE);
    }

    private class SendTrailers extends Callback.Nested
    {
        private final HttpFields trailers;

        private SendTrailers(Callback callback, HttpFields trailers)
        {
            super(callback);
            this.trailers = trailers;
        }

        @Override
        public void succeeded()
        {
            sendTrailersFrame(new MetaData(HttpVersion.HTTP_2, trailers), getCallback());
        }
    }

    private class TunnelSupportOverHTTP2 implements TunnelSupport
    {
        private final String protocol;
        private final EndPoint endPoint = new ServerHTTP2StreamEndPoint(_stream);

        private TunnelSupportOverHTTP2(String protocol)
        {
            this.protocol = protocol;
        }

        @Override
        public String getProtocol()
        {
            return protocol;
        }

        @Override
        public EndPoint getEndPoint()
        {
            return endPoint;
        }
    }

    private static class FailureTask implements Runnable
    {
        private final Runnable task;
        private final Callback callback;

        private FailureTask(Runnable task, Callback callback)
        {
            this.task = task;
            this.callback = Objects.requireNonNull(callback);
        }

        @Override
        public void run()
        {
            try
            {
                if (task != null)
                    task.run();
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public String toString()
        {
            return "%s[%s]".formatted(TypeUtil.toShortName(getClass()), task);
        }
    }
}
