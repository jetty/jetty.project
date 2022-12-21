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

package org.eclipse.jetty.http3.server.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP3 implements HttpStream
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP3.class);

    private final AutoLock lock = new AutoLock();
    private final long nanoTime = NanoTime.now();
    private final ServerHTTP3StreamConnection connection;
    private final HttpChannel httpChannel;
    private final HTTP3StreamServer stream;
    private MetaData.Request requestMetaData;
    private MetaData.Response responseMetaData;
    private Content.Chunk chunk;
    private boolean committed;
    private boolean expects100Continue;

    public HttpStreamOverHTTP3(ServerHTTP3StreamConnection connection, HttpChannel httpChannel, HTTP3StreamServer stream)
    {
        this.connection = connection;
        this.httpChannel = httpChannel;
        this.stream = stream;
    }

    @Override
    public String getId()
    {
        return String.valueOf(stream.getId());
    }

    @Override
    public long getNanoTime()
    {
        return nanoTime;
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            requestMetaData = (MetaData.Request)frame.getMetaData();

            Runnable handler = httpChannel.onRequest(requestMetaData);

            if (frame.isLast())
            {
                try (AutoLock ignored = lock.lock())
                {
                    chunk = Content.Chunk.EOF;
                }
            }

            HttpFields fields = requestMetaData.getFields();

            expects100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            boolean connect = requestMetaData instanceof MetaData.ConnectRequest;

            if (!connect)
                connection.setApplicationMode(true);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 request #{}/{}, {} {} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    requestMetaData.getMethod(), requestMetaData.getURI(), requestMetaData.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            return handler;
        }
        catch (BadMessageException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest() failure", x);
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
                chunk = this.chunk;
                this.chunk = Content.Chunk.next(chunk);
            }
            if (chunk != null)
                return chunk;

            Stream.Data data = stream.readData();
            if (data == null)
                return null;

            chunk = createChunk(data);
            data.release();

            // Some content is read, but the 100 Continue interim
            // response has not been sent yet, then don't bother
            // sending it later, as the client already sent the content.
            if (expects100Continue && chunk.hasRemaining())
                expects100Continue = false;

            try (AutoLock ignored = lock.lock())
            {
                this.chunk = chunk;
            }
        }
    }

    @Override
    public void demand()
    {
        boolean notify;
        try (AutoLock ignored = lock.lock())
        {
            // We may have a non-demanded chunk in case of trailers.
            notify = chunk != null;
        }
        if (notify)
        {
            Runnable task = httpChannel.onContentAvailable();
            if (task != null)
                connection.offer(task);
        }
        else
        {
            if (expects100Continue)
            {
                expects100Continue = false;
                send(requestMetaData, HttpGenerator.CONTINUE_100_INFO, false, null, Callback.NOOP);
            }
            stream.demand();
        }
    }

    public Runnable onDataAvailable()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 request data available #{}/{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()));
        }

        Stream.Data data = stream.readData();
        if (data == null)
        {
            stream.demand();
            return null;
        }

        Content.Chunk chunk = createChunk(data);
        data.release();

        try (AutoLock ignored = lock.lock())
        {
            this.chunk = chunk;
        }

        return httpChannel.onContentAvailable();
    }

    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getFields().asImmutable();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Request #{}/{}, trailer:{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), trailers);
        }
        try (AutoLock ignored = lock.lock())
        {
            chunk = new Trailers(trailers);
        }
        return httpChannel.onContentAvailable();
    }

    private Content.Chunk createChunk(Stream.Data data)
    {
        if (data == Stream.Data.EOF)
            return Content.Chunk.EOF;

        // As we are passing the ByteBuffer to the Chunk we need to retain.
        data.retain();
        return Content.Chunk.from(data.getByteBuffer(), data.isLast(), data);
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

    private void sendHeaders(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        this.responseMetaData = response;

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (HttpStatus.isInterim(response.getStatus()))
        {
            // Must not commit interim responses.

            if (hasContent)
            {
                callback.failed(new IllegalStateException("Interim response cannot have content"));
                return;
            }

            if (expects100Continue && response.getStatus() == HttpStatus.CONTINUE_100)
                expects100Continue = false;

            headersFrame = new HeadersFrame(response, false);
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
                    this.responseMetaData = new MetaData.Response(
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
                headersFrame = new HeadersFrame(response, false);
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers();
                    if (trailers == null)
                    {
                        dataFrame = new DataFrame(content, true);
                    }
                    else
                    {
                        dataFrame = new DataFrame(content, false);
                        trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                    }
                }
                else
                {
                    dataFrame = new DataFrame(content, false);
                }
            }
            else
            {
                if (lastContent)
                {
                    if (isTunnel(request, response))
                    {
                        headersFrame = new HeadersFrame(response, false);
                    }
                    else
                    {
                        HttpFields trailers = retrieveTrailers();
                        if (trailers == null)
                        {
                            headersFrame = new HeadersFrame(response, true);
                        }
                        else
                        {
                            headersFrame = new HeadersFrame(response, false);
                            trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                        }
                    }
                }
                else
                {
                    headersFrame = new HeadersFrame(response, false);
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Response #{}/{}:{}{} {}{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), HttpVersion.HTTP_3, response.getStatus(),
                System.lineSeparator(), response.getFields());
        }

        CompletableFuture<Stream> cf = stream.respond(headersFrame);

        DataFrame df = dataFrame;
        if (df != null)
            cf = cf.thenCompose(s -> s.data(df));

        HeadersFrame tf = trailersFrame;
        if (tf != null)
            cf = cf.thenCompose(s -> s.trailer(tf));

        callback.completeWith(cf);
    }

    private void sendContent(MetaData.Request request, ByteBuffer content, boolean lastContent, Callback callback)
    {
        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        if (hasContent || (lastContent && !isTunnel(request, responseMetaData)))
        {
            if (!hasContent)
                content = BufferUtil.EMPTY_BUFFER;
            if (lastContent)
            {
                HttpFields trailers = retrieveTrailers();
                if (trailers == null)
                {
                    callback.completeWith(sendDataFrame(content, true, true));
                }
                else
                {
                    if (hasContent)
                    {
                        callback.completeWith(sendDataFrame(content, lastContent, false)
                            .thenCompose(s -> sendTrailerFrame(trailers)));
                    }
                    else
                    {
                        callback.completeWith(sendTrailerFrame(trailers));
                    }
                }
            }
            else
            {
                callback.completeWith(sendDataFrame(content, false, false));
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    private HttpFields retrieveTrailers()
    {
        Supplier<HttpFields> supplier = responseMetaData.getTrailersSupplier();
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

    private CompletableFuture<Stream> sendDataFrame(ByteBuffer content, boolean lastContent, boolean endStream)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Response #{}/{}: {} content bytes{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                content.remaining(), lastContent ? " (last chunk)" : "");
        }
        DataFrame frame = new DataFrame(content, endStream);
        return stream.data(frame);
    }

    private CompletableFuture<Stream> sendTrailerFrame(HttpFields trailers)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Response #{}/{}: trailer{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), trailers);
        }

        HeadersFrame frame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
        return stream.trailer(frame);
    }

    @Override
    public boolean isCommitted()
    {
        return committed;
    }

    public boolean isIdle()
    {
        // TODO: is this necessary?
        return true;
    }

    @Override
    public void succeeded()
    {
        httpChannel.recycle();

        // If the stream is not closed, it is still reading the request content.
        // Send a reset to the other end so that it stops sending data.
        if (!stream.isClosed())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("HTTP3 Response #{}/{}: unconsumed request content, resetting stream", stream.getId(), Integer.toHexString(stream.getSession().hashCode()));
            stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new IOException("unconsumed content"));
        }
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP3 Response #{}/{} aborted", stream.getId(), Integer.toHexString(stream.getSession().hashCode()));
        stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
    }

    public void onIdleTimeout(Throwable failure, BiConsumer<Runnable, Boolean> consumer)
    {
        Runnable runnable = httpChannel.onFailure(failure);
        boolean idle = !httpChannel.isRequestHandled();
        consumer.accept(runnable, idle);
    }

    public Runnable onFailure(Throwable failure)
    {
        return httpChannel.onFailure(failure);
    }
}
