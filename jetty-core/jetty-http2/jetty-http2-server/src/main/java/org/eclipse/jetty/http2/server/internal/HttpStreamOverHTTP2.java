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

package org.eclipse.jetty.http2.server.internal;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP2 implements HttpStream, HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP2.class);

    private final AutoLock lock = new AutoLock();
    private final HTTP2ServerConnection _connection;
    private final HttpChannel _httpChannel;
    private final HTTP2Stream _stream;
    private final long _nanoTime;
    private MetaData.Request _requestMetaData;
    private MetaData.Response _responseMetaData;
    private TunnelSupport tunnelSupport;
    private Content.Chunk _chunk;
    private boolean committed;
    private boolean _demand;
    private boolean _expects100Continue;

    public HttpStreamOverHTTP2(HTTP2ServerConnection connection, HttpChannel httpChannel, HTTP2Stream stream)
    {
        _connection = connection;
        _httpChannel = httpChannel;
        _stream = stream;
        _nanoTime = NanoTime.now();
    }

    @Override
    public String getId()
    {
        return String.valueOf(_stream.getId());
    }

    @Override
    public long getNanoTimeStamp()
    {
        return _nanoTime;
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            _requestMetaData = (MetaData.Request)frame.getMetaData();

            Runnable handler = _httpChannel.onRequest(_requestMetaData);

            if (frame.isEndStream())
            {
                try (AutoLock ignored = lock.lock())
                {
                    _chunk = Content.Chunk.EOF;
                }
            }

            HttpFields fields = _requestMetaData.getFields();

            _expects100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            if (_requestMetaData instanceof MetaData.ConnectRequest)
                tunnelSupport = new TunnelSupportOverHTTP2(_requestMetaData.getProtocol());

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP2 request #{}/{}, {} {} {}{}{}",
                    _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                    _requestMetaData.getMethod(), _requestMetaData.getURI(), _requestMetaData.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            return handler;
        }
        catch (BadMessageException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest", x);
            return () -> onBadMessage(x);
        }
        catch (Throwable x)
        {
            return () -> onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
        }
    }

    private void onBadMessage(BadMessageException x)
    {
        // TODO
    }

    @Override
    public Content.Chunk read()
    {
        while (true)
        {
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

            chunk = createChunk(data);
            data.release();

            // Some content is read, but the 100 Continue interim
            // response has not been sent yet, then don't bother
            // sending it later, as the client already sent the content.
            if (_expects100Continue && chunk.hasRemaining())
                _expects100Continue = false;

            try (AutoLock ignored = lock.lock())
            {
                _chunk = chunk;
            }
        }
    }

    @Override
    public void demand()
    {
        boolean notify = false;
        boolean demand = false;
        try (AutoLock ignored = lock.lock())
        {
            // We may have a non-demanded chunk in case of trailers.
            if (_chunk != null)
                notify = true;
            else if (!_demand)
                demand = _demand = true;
        }
        if (notify)
        {
            Runnable task = _httpChannel.onContentAvailable();
            if (task != null)
                _connection.offerTask(task, false);
        }
        else if (demand)
        {
            if (_expects100Continue)
            {
                _expects100Continue = false;
                send(_requestMetaData, HttpGenerator.CONTINUE_100_INFO, false, null, Callback.NOOP);
            }
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
        HttpFields trailers = frame.getMetaData().getFields().asImmutable();
        try (AutoLock ignored = lock.lock())
        {
            _demand = false;
            _chunk = new Trailers(trailers);
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
            return Content.Chunk.EOF;

        // We need to retain because we are passing the ByteBuffer to the Chunk.
        data.retain();
        return Content.Chunk.from(frame.getData(), frame.isEndStream(), data);
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

            if (_expects100Continue && response.getStatus() == HttpStatus.CONTINUE_100)
                _expects100Continue = false;

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
                        response.getHttpVersion(),
                        response.getStatus(),
                        response.getReason(),
                        response.getFields(),
                        realContentLength,
                        response.getTrailersSupplier()
                    );
                }
                else if (hasContent && contentLength != realContentLength)
                {
                    callback.failed(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, String.format("Incorrect Content-Length %d!=%d", contentLength, realContentLength)));
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
                System.lineSeparator(), response.getFields());
        }

        _stream.send(new HTTP2Stream.FrameList(headersFrame, dataFrame, trailersFrame), callback);
    }

    private void sendContent(MetaData.Request request, ByteBuffer content, boolean last, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (last && !isTunnel(request, _responseMetaData)))
        {
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
    public boolean isPushSupported()
    {
        return _stream.getSession().isPushEnabled();
    }

    @Override
    public void push(MetaData.Request request)
    {
        if (!_stream.getSession().isPushEnabled())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP/2 push disabled for {}", request);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("HTTP/2 push {}", request);

        _stream.push(new PushPromiseFrame(_stream.getId(), request), new Promise<>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                _connection.push((HTTP2Stream)pushStream, request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not HTTP/2 push {}", request, x);
            }
        }, null); // TODO: handle reset from the client ?
    }

    public Runnable onPushRequest(MetaData.Request request)
    {
        try
        {
            Runnable task = _httpChannel.onRequest(request);
            _httpChannel.getRequest().setAttribute("org.eclipse.jetty.pushed", Boolean.TRUE);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP/2 push request #{}/{}:{}{} {} {}{}{}",
                        _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()), System.lineSeparator(),
                        request.getMethod(), request.getURI(), request.getHttpVersion(),
                        System.lineSeparator(), request.getFields());
            }

            return task;
        }
        catch (BadMessageException x)
        {
            return () -> onBadMessage(x);
        }
        catch (Throwable x)
        {
            return () -> onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
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
        if (HttpMethod.CONNECT.is(_requestMetaData.getMethod()))
            return null;
        return HttpStream.super.consumeAvailable();
    }

    @Override
    public void onTimeout(Throwable failure, BiConsumer<Runnable, Boolean> consumer)
    {
        Runnable task = _httpChannel.onFailure(failure);
        boolean idle = !_httpChannel.isRequestHandled();
        consumer.accept(task, idle);
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        Runnable runnable = _httpChannel.onFailure(failure);
        return () ->
        {
            if (runnable != null)
                runnable.run();
            callback.succeeded();
        };
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
                // If the stream is not closed, it is still reading the request content.
                // Send a reset to the other end so that it stops sending data.
                if (LOG.isDebugEnabled())
                    LOG.debug("HTTP2 response #{}/{}: unconsumed request content, resetting stream", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
                _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            }
        }
        _httpChannel.recycle();
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 response #{}/{} aborted", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
        _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
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
}
