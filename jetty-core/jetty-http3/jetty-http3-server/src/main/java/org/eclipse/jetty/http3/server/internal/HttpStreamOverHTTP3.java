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

package org.eclipse.jetty.http3.server.internal;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpStreamOverHTTP3 implements HttpStream
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpStreamOverHTTP3.class);

    private final AutoLock lock = new AutoLock();
    private final ServerHTTP3StreamConnection connection;
    private final HttpChannel httpChannel;
    private final HTTP3StreamServer stream;
    private MetaData.Request requestMetaData;
    private MetaData.Response responseMetaData;
    private Content.Chunk chunk;
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

    public Runnable onRequest(HeadersFrame frame)
    {
        try
        {
            requestMetaData = (MetaData.Request)frame.getMetaData();

            // Grab freshly initialized ComplianceViolation.Listener here, no need to reinitialize.
            ComplianceViolation.Listener listener = httpChannel.getComplianceViolationListener();
            Runnable handler = httpChannel.onRequest(requestMetaData);
            Request request = this.httpChannel.getRequest();
            listener.onRequestBegin(request);
            // Note UriCompliance is done by HandlerInvoker
            HttpCompliance httpCompliance = httpChannel.getConnectionMetaData().getHttpConfiguration().getHttpCompliance();
            HttpCompliance.checkHttpCompliance(requestMetaData, httpCompliance, listener);

            if (frame.isLast())
            {
                try (AutoLock ignored = lock.lock())
                {
                    chunk = Content.Chunk.EOF;
                }
            }

            HttpFields fields = requestMetaData.getHttpFields();

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 request #{}/{}, {} {} {}{}{}",
                    stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                    requestMetaData.getMethod(), requestMetaData.getHttpURI(), requestMetaData.getHttpVersion(),
                    System.lineSeparator(), fields);
            }

            InvocationType invocationType = Invocable.getInvocationType(handler);
            return new ReadyTask(invocationType, handler)
            {
                @Override
                public void run()
                {
                    if (stream.isClosed())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("HTTP3 request #{}/{} skipped handling, stream already closed {}",
                                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                                stream);
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
            if (LOG.isDebugEnabled())
                LOG.debug("onRequest() failure", x);
            HttpException httpException = x instanceof HttpException http ? http : new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, x);
            return onBadMessage(httpException);
        }
    }

    private Runnable onBadMessage(HttpException x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("badMessage {} {}", this, x);

        Throwable failure = (Throwable)x;
        return httpChannel.onFailure(failure);
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

            // The data instance should be released after readData() above;
            // the chunk is stored below for later use, so should be retained;
            // the two actions cancel each other, no need to further retain or release.
            chunk = createChunk(data);

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

        // The data instance should be released after readData() above;
        // the chunk is stored below for later use, so should be retained;
        // the two actions cancel each other, no need to further retain or release.
        Content.Chunk chunk = createChunk(data);

        try (AutoLock ignored = lock.lock())
        {
            this.chunk = chunk;
        }

        return httpChannel.onContentAvailable();
    }

    public Runnable onTrailer(HeadersFrame frame)
    {
        HttpFields trailers = frame.getMetaData().getHttpFields().asImmutable();
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
        {
            data.release();
            return Content.Chunk.EOF;
        }
        return Content.Chunk.asChunk(data.getByteBuffer(), data.isLast(), data);
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
                System.lineSeparator(), response.getHttpFields());
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
                        callback.completeWith(sendDataFrame(content, false, false)
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
    public long getIdleTimeout()
    {
        return stream.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeoutMs)
    {
        stream.setIdleTimeout(idleTimeoutMs);
    }

    @Override
    public boolean isCommitted()
    {
        return committed;
    }

    @Override
    public Throwable consumeAvailable()
    {
        if (getTunnelSupport() != null)
            return null;
        Throwable result = HttpStream.consumeAvailable(this, httpChannel.getConnectionMetaData().getHttpConfiguration());
        if (result != null)
        {
            if (chunk != null)
                chunk.release();
            chunk = Content.Chunk.from(result, true);
        }
        return result;
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
            stream.reset(HTTP3ErrorCode.NO_ERROR.code(), CONTENT_NOT_CONSUMED);
        }
    }

    @Override
    public void failed(Throwable x)
    {
        HTTP3ErrorCode errorCode = x == HttpStream.CONTENT_NOT_CONSUMED ? HTTP3ErrorCode.NO_ERROR : HTTP3ErrorCode.REQUEST_CANCELLED_ERROR;
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP3 Response #{}/{} failed {}", stream.getId(), Integer.toHexString(stream.getSession().hashCode()), errorCode, x);
        stream.reset(errorCode.code(), x);
    }

    public void onIdleTimeout(TimeoutException failure, BiConsumer<Runnable, Boolean> consumer)
    {
        Runnable runnable = httpChannel.onIdleTimeout(failure);
        boolean idle = !httpChannel.isRequestHandled();
        consumer.accept(runnable, idle);
    }

    public Runnable onFailure(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (chunk != null)
                chunk.release();
            chunk = Content.Chunk.from(failure, true);
        }
        connection.onFailure(failure);

        boolean remote = failure instanceof EOFException;
        return remote ? httpChannel.onRemoteFailure(new EofException(failure)) : httpChannel.onFailure(failure);
    }
}
