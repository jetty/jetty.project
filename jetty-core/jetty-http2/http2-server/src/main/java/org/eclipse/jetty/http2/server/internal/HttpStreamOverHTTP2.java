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
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP2 implements HttpStream, HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP2.class);

    private final HTTP2ServerConnection _connection;
    private final HttpChannelState _httpChannel;
    private final IStream _stream;
    private final long _nanoTimeStamp;
    private Content _content;
    private MetaData.Response _metaData;
    private boolean committed;
    private boolean _demand;

    public HttpStreamOverHTTP2(HTTP2ServerConnection connection, HttpChannelState httpChannel, IStream stream)
    {
        _connection = connection;
        _httpChannel = httpChannel;
        _stream = stream;
        _nanoTimeStamp = System.nanoTime();
    }

    @Override
    public String getId()
    {
        return String.valueOf(_stream.getId());
    }

    @Override
    public long getNanoTimeStamp()
    {
        return _nanoTimeStamp;
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            MetaData.Request request = (MetaData.Request)frame.getMetaData();

            Runnable handler = _httpChannel.onRequest(request);

            if (frame.isEndStream())
                _content = Content.EOF;

            HttpFields fields = request.getFields();

            // TODO: handle 100 continue.
//            _expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP2 request #{}/{}, {} {} {}{}{}",
                    _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                    request.getMethod(), request.getURI(), request.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            return handler;
        }
        catch (BadMessageException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest", x);
            onBadMessage(x);
            return null;
        }
        catch (Throwable x)
        {
            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
            return null;
        }
    }

    private void onBadMessage(BadMessageException x)
    {
        // TODO
    }

    @Override
    public Content readContent()
    {
        while (true)
        {
            Content content = _content;
            _content = Content.next(content);
            if (content != null)
                return content;

            IStream.Data data = _stream.readData();
            if (data == null)
                return null;

            _content = newContent(data.frame(), data::complete);
        }
    }

    @Override
    public void demandContent()
    {
        if (!_demand)
        {
            _demand = true;
            _stream.demand(1);
        }
    }

    @Override
    public Runnable onData(DataFrame frame, Callback callback)
    {
        _demand = false;
        _content = newContent(frame, callback::succeeded);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}: {} bytes of {} content",
                _stream.getId(),
                Integer.toHexString(_stream.getSession().hashCode()),
                frame.remaining(),
                frame.isEndStream() ? "last" : "some");
        }

        return _httpChannel.onContentAvailable();
    }

    private Content.Abstract newContent(DataFrame frame, Runnable complete)
    {
        return new Content.Abstract(false, frame.isEndStream())
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return frame.getData();
            }

            @Override
            public void release()
            {
                complete.run();
            }
        };
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getFields().asImmutable();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}, trailer:{}{}",
                    _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                    System.lineSeparator(), trailers);
        }
        _content = new Content.Trailers(trailers);
        return _httpChannel.onContentAvailable();
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
        // Nothing to do here.
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... buffers)
    {
        if (buffers.length > 1)
            throw new IllegalStateException();

        ByteBuffer content = buffers.length == 0 ? BufferUtil.EMPTY_BUFFER : buffers[0];
        if (response != null)
            sendHeaders(request, response, content, last, callback);
        else
            sendContent(request, content, last, callback);
    }

    private void sendHeaders(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean last, Callback callback)
    {
        _metaData = response;

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        int status = response.getStatus();
        boolean interimResponse = status == HttpStatus.CONTINUE_100 || status == HttpStatus.PROCESSING_102;
        int streamId = _stream.getId();
        if (interimResponse)
        {
            // Must not commit interim responses.
            if (hasContent)
            {
                callback.failed(new IllegalStateException("Interim response cannot have content"));
                return;
            }
            headersFrame = new HeadersFrame(streamId, _metaData, null, false);
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
                    _metaData = new MetaData.Response(
                        response.getHttpVersion(),
                        response.getStatus(),
                        response.getReason(),
                        response.getFields(),
                        realContentLength,
                        response.getTrailerSupplier()
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
                headersFrame = new HeadersFrame(streamId, _metaData, null, false);
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
                    if (isTunnel(request, _metaData))
                    {
                        headersFrame = new HeadersFrame(streamId, _metaData, null, false);
                    }
                    else
                    {
                        HttpFields trailers = retrieveTrailers();
                        if (trailers == null)
                        {
                            headersFrame = new HeadersFrame(streamId, _metaData, null, true);
                        }
                        else
                        {
                            headersFrame = new HeadersFrame(streamId, _metaData, null, false);
                            trailersFrame = new HeadersFrame(streamId, new MetaData(HttpVersion.HTTP_2, trailers), null, true);
                        }
                    }
                }
                else
                {
                    headersFrame = new HeadersFrame(streamId, _metaData, null, false);
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Response #{}/{}:{}{} {}{}{}",
                _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()),
                System.lineSeparator(), HttpVersion.HTTP_2, _metaData.getStatus(),
                System.lineSeparator(), _metaData.getFields());
        }
        _stream.send(new IStream.FrameList(headersFrame, dataFrame, trailersFrame), callback);
    }

    private void sendContent(MetaData.Request request, ByteBuffer content, boolean last, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (last && !isTunnel(request, _metaData)))
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
        Supplier<HttpFields> supplier = _metaData.getTrailerSupplier();
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
                _connection.push((IStream)pushStream, request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not HTTP/2 push {}", request, x);
            }
        }, new Stream.Listener.Adapter()); // TODO: handle reset from the client ?
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
            onBadMessage(x);
            return null;
        }
        catch (Throwable x)
        {
            onBadMessage(new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, x));
            return null;
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
    public boolean isComplete()
    {
        // TODO
        return false;
    }

    @Override
    public void setUpgradeConnection(Connection connection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isIdle()
    {
        // TODO: is this necessary?
        return false;
    }

    @Override
    public Connection upgrade()
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        Runnable runnable = _httpChannel.onError(failure);
        if (runnable != null)
            consumer.accept(runnable);
        return !_httpChannel.isHandled();
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        Runnable runnable = _httpChannel.onError(failure);
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
        // If the stream is not closed, it is still reading the request content.
        // Send a reset to the other end so that it stops sending data.
        if (!_stream.isClosed())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP2 response #{}/{}: unconsumed request content, resetting stream", _stream.getId(), Integer.toHexString(_stream.getSession().hashCode()));
            _stream.reset(new ResetFrame(_stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        }
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
}
