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
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.internal.HTTP2Channel;
import org.eclipse.jetty.io.ByteBufferAccumulator;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP2 implements HttpStream, HTTP2Channel.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP2.class);

    private final HttpChannel _httpChannel;
    private final IStream _stream;
    private final long _nanoTimeStamp;
    private Content _content;
    private MetaData.Response _metaData;
    private boolean committed;

    public HttpStreamOverHTTP2(HttpChannel httpChannel, IStream stream)
    {
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

            HttpFields fields = request.getFields();
            // TODO: handle 100 continue.
//            _expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            boolean endStream = frame.isEndStream();
            if (endStream)
                onRequestComplete();

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP2 Request #{}/{}, {} {} {}{}{}",
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
        // TODO: should be locked?
        Content content = _content;
        _content = null;
        return content;
    }

    @Override
    public void demandContent()
    {
        // TODO: make it idempotent.
        _stream.demand(1);
    }

    @Override
    public void prepareResponse(HttpFields.Mutable headers)
    {
    }

    @Override
    public void send(MetaData.Request request, MetaData.Response response, boolean lastContent, Callback callback, ByteBuffer... content)
    {
        // TODO: convert this to use IStream.FrameList.
        ByteBufferAccumulator accumulator = new ByteBufferAccumulator();
        for (ByteBuffer buffer : content)
        {
            accumulator.copyBuffer(buffer);
        }
        ByteBuffer buffer = accumulator.toByteBuffer();

        if (response != null)
            sendHeaders(request, response, lastContent, buffer, callback);
        else
            sendContent(request, lastContent, buffer, callback);
    }

    private void sendHeaders(MetaData.Request request, MetaData.Response response, boolean lastContent, ByteBuffer content, Callback callback)
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
            if (lastContent)
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
                if (lastContent)
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
                if (lastContent)
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

    private void sendContent(MetaData.Request request, boolean lastContent, ByteBuffer content, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (lastContent && !isTunnel(request, _metaData)))
        {
            if (lastContent)
            {
                HttpFields trailers = retrieveTrailers();
                if (trailers == null)
                {
                    sendDataFrame(content, true, true, callback);
                }
                else
                {
                    SendTrailers sendTrailers = new SendTrailers(callback, trailers);
                    if (hasContent)
                    {
                        sendDataFrame(content, true, false, callback);
                    }
                    else
                    {
                        sendTrailers.succeeded();
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
    public void push(final MetaData.Request request)
    {
/*
        if (!_stream.getSession().isPushEnabled())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP/2 Push disabled for {}", request);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("HTTP/2 Push {}", request);

        _stream.push(new PushPromiseFrame(_stream.getId(), request), new Promise<>()
        {
            @Override
            public void succeeded(Stream pushStream)
            {
                connection.push(connector, (IStream)pushStream, request);
            }

            @Override
            public void failed(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not push {}", request, x);
            }
        }, new Stream.Listener.Adapter()); // TODO: handle reset from the client ?
*/
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
    public boolean upgrade()
    {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Runnable onData(DataFrame frame, Callback callback)
    {
        ByteBuffer buffer = frame.getData();
        int length = buffer.remaining();
        _content = new Content.Abstract(false, frame.isEndStream())
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return buffer;
            }

            @Override
            public void release()
            {
                callback.succeeded();
            }
        };

        Runnable onContentAvailable = _httpChannel.onContentAvailable();
        if (onContentAvailable != null)
            onContentAvailable.run();

        boolean endStream = frame.isEndStream();
        if (endStream)
            onRequestComplete();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}/{}: {} bytes of {} content",
                _stream.getId(),
                Integer.toHexString(_stream.getSession().hashCode()),
                length,
                endStream ? "last" : "some");
        }

        // TODO: should we return the onContentAvailable Runnable here?
        return null;
    }

    private void onRequestComplete()
    {
        // TODO: HttpChannel event?
    }

    @Override
    public Runnable onTrailer(HeadersFrame frame)
    {
        // TODO
        return null;
    }

    @Override
    public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        Runnable runnable = _httpChannel.onError(failure);
        if (runnable != null)
            consumer.accept(runnable);
        return false;
    }

    @Override
    public Runnable onFailure(Throwable failure, Callback callback)
    {
        Runnable runnable = _httpChannel.onError(failure);
        return () ->
        {
            runnable.run();
            callback.succeeded();
        };
    }

    @Override
    public boolean isIdle()
    {
        // TODO: is this necessary?
        return false;
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
