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
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP3 implements HttpStream
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP3.class);

    private final long nanoTime = System.nanoTime();
    private final ServerHTTP3StreamConnection connection;
    private final HttpChannel httpChannel;
    private final HTTP3StreamServer stream;
    private Content content;
    private MetaData.Response metaData;
    private boolean committed;

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
    public long getNanoTimeStamp()
    {
        return nanoTime;
    }

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            MetaData.Request request = (MetaData.Request)frame.getMetaData();

            Runnable handler = httpChannel.onRequest(request);

            if (frame.isLast())
                content = Content.EOF;

            HttpFields fields = request.getFields();

            // TODO: handle 100 continue.
//            expect100Continue = fields.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

            boolean connect = request instanceof MetaData.ConnectRequest;

            if (!connect)
                connection.setApplicationMode(true);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 request #{}/{}, {} {} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    request.getMethod(), request.getURI(), request.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            return handler;
        }
        catch (BadMessageException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest() failure", x);
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
            Content content = this.content;
            this.content = Content.next(content);
            if (content != null)
                return content;

            Stream.Data data = stream.readData();
            if (data == null)
                return null;

            this.content = newContent(data);
        }
    }

    @Override
    public void demandContent()
    {
        stream.demand();
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

        content = newContent(data);
        return httpChannel.onContentAvailable();
    }

    private Content.Abstract newContent(Stream.Data data)
    {
        return new Content.Abstract(false, data.isLast())
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return data.getByteBuffer();
            }

            @Override
            public void release()
            {
                data.complete();
            }
        };
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
        content = new Content.Trailers(trailers);
        return httpChannel.onContentAvailable();
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

    private void sendHeaders(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
    {
        metaData = response;

        HeadersFrame headersFrame;
        DataFrame dataFrame = null;
        HeadersFrame trailersFrame = null;

        boolean isHeadRequest = HttpMethod.HEAD.is(request.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;
        int status = response.getStatus();
        boolean interimResponse = status == HttpStatus.CONTINUE_100 || status == HttpStatus.PROCESSING_102;
        if (interimResponse)
        {
            // Must not commit interim responses.
            if (hasContent)
            {
                callback.failed(new IllegalStateException("Interim response cannot have content"));
                return;
            }
            headersFrame = new HeadersFrame(metaData, false);
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
                    metaData = new MetaData.Response(
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
                headersFrame = new HeadersFrame(metaData, false);
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
                    if (isTunnel(request, metaData))
                    {
                        headersFrame = new HeadersFrame(metaData, false);
                    }
                    else
                    {
                        HttpFields trailers = retrieveTrailers();
                        if (trailers == null)
                        {
                            headersFrame = new HeadersFrame(metaData, true);
                        }
                        else
                        {
                            headersFrame = new HeadersFrame(metaData, false);
                            trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                        }
                    }
                }
                else
                {
                    headersFrame = new HeadersFrame(metaData, false);
                }
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 Response #{}/{}:{}{} {}{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), HttpVersion.HTTP_3, metaData.getStatus(),
                System.lineSeparator(), metaData.getFields());
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
        if (hasContent || (lastContent && !isTunnel(request, metaData)))
        {
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
        Supplier<HttpFields> supplier = metaData.getTrailerSupplier();
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
    public boolean isPushSupported()
    {
        return false;
    }

    @Override
    public void push(MetaData.Request request)
    {
        throw new UnsupportedOperationException();
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

    public boolean isIdle()
    {
        // TODO: is this necessary?
        return true;
    }

    @Override
    public void setUpgradeConnection(Connection connection)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Connection upgrade()
    {
        return null;
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

    public boolean onIdleTimeout(Throwable failure, Consumer<Runnable> consumer)
    {
        Runnable runnable = httpChannel.onFailure(failure);
        if (runnable != null)
            consumer.accept(runnable);
        return !httpChannel.isRequestHandled();
    }

    public Runnable onFailure(Throwable failure)
    {
        return httpChannel.onFailure(failure);
    }
}
