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

package org.eclipse.jetty.client.transport.internal;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpSenderOverHTTP extends HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSenderOverHTTP.class);

    private final IteratingCallback headersCallback = new HeadersCallback();
    private final IteratingCallback contentCallback = new ContentCallback();
    private final HttpGenerator generator = new HttpGenerator();
    private MetaData.Request metaData;
    private ByteBuffer content;
    private boolean lastContent;
    private Callback callback;
    private boolean shutdown;

    public HttpSenderOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    public HttpGenerator getHttpGenerator()
    {
        return generator;
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentByteBuffer, boolean lastContent, Callback callback)
    {
        try
        {
            this.content = contentByteBuffer;
            this.lastContent = lastContent;
            this.callback = callback;
            HttpRequest request = exchange.getRequest();
            Content.Source requestContent = request.getBody();
            long contentLength = requestContent == null ? -1 : requestContent.getLength();
            // URI validations already performed by using the java.net.URI class.
            HttpURI uri = HttpURI.from(null, null, -1, request.getPath(), request.getQuery(), null);
            metaData = new MetaData.Request(request.getMethod(), uri, request.getVersion(), request.getHeaders(), contentLength, request.getTrailersSupplier());
            if (LOG.isDebugEnabled())
                LOG.debug("Sending headers with content {} last={} for {}", BufferUtil.toDetailString(contentByteBuffer), lastContent, exchange.getRequest());
            headersCallback.iterate();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to send headers on exchange {}", exchange, x);
            callback.failed(x);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, ByteBuffer contentByteBuffer, boolean lastContent, Callback callback)
    {
        try
        {
            this.content = contentByteBuffer;
            this.lastContent = lastContent;
            this.callback = callback;
            if (LOG.isDebugEnabled())
                LOG.debug("Sending content {} last={} for {}", BufferUtil.toDetailString(contentByteBuffer), lastContent, exchange.getRequest());
            contentCallback.iterate();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to send content on {}", exchange, x);
            callback.failed(x);
        }
    }

    @Override
    protected void reset()
    {
        headersCallback.reset();
        contentCallback.reset();
        generator.reset();
        super.reset();
    }

    @Override
    protected void dispose()
    {
        generator.abort();
        super.dispose();
        shutdownOutput();
    }

    private void shutdownOutput()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Request shutdown output {}", getHttpExchange().getRequest());
        shutdown = true;
    }

    protected boolean isShutdown()
    {
        return shutdown;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", super.toString(), generator);
    }

    private class HeadersCallback extends IteratingCallback
    {
        private RetainableByteBuffer.Mutable headerBuffer;
        private RetainableByteBuffer.Mutable chunkBuffer;
        private boolean generated;

        private HeadersCallback()
        {
            super(false);
        }

        @Override
        protected Action process() throws Exception
        {
            HttpClient httpClient = getHttpChannel().getHttpDestination().getHttpClient();
            HttpExchange exchange = getHttpExchange();
            WritableBufferPool bufferPool = org.eclipse.jetty.io.WritableBufferPool.wrap(httpClient.getByteBufferPool());
            int requestHeadersSize = httpClient.getRequestBufferSize();
            int maxRequestHeadersSize = httpClient.getMaxRequestHeadersSize();
            boolean useDirectByteBuffers = httpClient.isUseOutputDirectByteBuffers();
            int chunkMaxLength = generator.getChunkMaxLength();
            RetainableByteBuffer contentBuffer = RetainableByteBuffer.wrap(content);
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(metaData, headerBuffer, chunkBuffer, contentBuffer, lastContent);

                if (LOG.isDebugEnabled())
                    LOG.debug("Generated headers ({} bytes), chunk ({} bytes), content ({} bytes) - {}/{} for {}",
                        headerBuffer == null ? -1 : headerBuffer.remaining(),
                        chunkBuffer == null ? -1 : chunkBuffer.remaining(),
                        content == null ? -1 : content.remaining(),
                        result, generator, exchange.getRequest());
                switch (result)
                {
                    case NEED_HEADER:
                    {
                        int maxHeadersSize = maxRequestHeadersSize;
                        if (maxHeadersSize < 0)
                            maxHeadersSize = requestHeadersSize;
                        generator.setMaxHeaderBytes(maxHeadersSize);
                        headerBuffer = bufferPool.acquire(requestHeadersSize, useDirectByteBuffers);
                        break;
                    }
                    case HEADER_OVERFLOW:
                    {
                        if (maxRequestHeadersSize > 0 && maxRequestHeadersSize > requestHeadersSize)
                        {
                            generator.reset();
                            headerBuffer.release();
                            headerBuffer = bufferPool.acquire(maxRequestHeadersSize, useDirectByteBuffers);
                            requestHeadersSize = maxRequestHeadersSize;
                            break;
                        }
                        else
                        {
                            throw new IllegalArgumentException("Request headers too large");
                        }
                    }
                    case NEED_CHUNK:
                    {
                        chunkBuffer = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, useDirectByteBuffers);
                        break;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        chunkBuffer = bufferPool.acquire(requestHeadersSize, useDirectByteBuffers);
                        break;
                    }
                    case FLUSH:
                    {
                        boolean sliced = false;
                        if (generator.isChunking() && contentBuffer.remaining() > chunkMaxLength)
                        {
                            contentBuffer = contentBuffer.sliceAndConsume(chunkMaxLength);
                            sliced = true;
                        }

                        long bytes = (headerBuffer != null ? headerBuffer.remaining() : 0) +
                            (chunkBuffer != null ? chunkBuffer.remaining() : 0) +
                            contentBuffer.remaining();
                        getHttpChannel().getHttpConnection().addBytesOut(bytes);

                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        RetainableByteBuffer toWrite = RetainableByteBuffer.wrap(headerBuffer, chunkBuffer, contentBuffer);
                        endPoint.write(toWrite, this);

                        toWrite.release();
                        if (sliced)
                            contentBuffer.release();

                        generated = true;
                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        return Action.SUCCEEDED;
                    }
                    case CONTINUE:
                    {
                        if (generated)
                            return Action.SUCCEEDED;
                        break;
                    }
                    case DONE:
                    {
                        if (generated)
                            return Action.SUCCEEDED;
                        // The headers have already been generated by some
                        // other thread, perhaps by a concurrent abort().
                        throw new HttpRequestException("Could not generate headers", exchange.getRequest());
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }

        @Override
        protected void onSuccess()
        {
            headerBuffer = Retainable.dispose(headerBuffer);
            if (chunkBuffer != null)
                chunkBuffer.clear();
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            headerBuffer = Retainable.dispose(headerBuffer);
            chunkBuffer = Retainable.dispose(chunkBuffer);
            super.onCompleted(causeOrNull);
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            callback.failed(cause);
        }
    }

    private class ContentCallback extends IteratingCallback
    {
        private RetainableByteBuffer.Mutable chunkBuffer;

        public ContentCallback()
        {
            super(false);
        }

        @Override
        protected Action process() throws Exception
        {
            HttpClient httpClient = getHttpChannel().getHttpDestination().getHttpClient();
            WritableBufferPool bufferPool = org.eclipse.jetty.io.WritableBufferPool.wrap(httpClient.getByteBufferPool());
            boolean useDirectByteBuffers = httpClient.isUseOutputDirectByteBuffers();
            int chunkMaxLength = generator.getChunkMaxLength();
            RetainableByteBuffer contentBuffer = RetainableByteBuffer.wrap(content);
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(null, null, chunkBuffer, contentBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated content ({} bytes, last={}) - {}/{}",
                        content == null ? -1 : content.remaining(),
                        lastContent, result, generator);
                switch (result)
                {
                    case NEED_CHUNK:
                    {
                        chunkBuffer = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, useDirectByteBuffers);
                        break;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        chunkBuffer = bufferPool.acquire(httpClient.getRequestBufferSize(), useDirectByteBuffers);
                        break;
                    }
                    case FLUSH:
                    {
                        boolean sliced = false;
                        if (generator.isChunking() && contentBuffer.remaining() > chunkMaxLength)
                        {
                            contentBuffer = contentBuffer.sliceAndConsume(chunkMaxLength);
                            sliced = true;
                        }

                        long bytes = (chunkBuffer != null ? chunkBuffer.remaining() : 0) + contentBuffer.remaining();
                        getHttpChannel().getHttpConnection().addBytesOut(bytes);

                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        RetainableByteBuffer toWrite = chunkBuffer != null ? RetainableByteBuffer.wrap(chunkBuffer, contentBuffer) : contentBuffer;
                        endPoint.write(toWrite, this);

                        if (chunkBuffer != null)
                            toWrite.release();
                        if (sliced)
                            contentBuffer.release();

                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    case DONE:
                    {
                        return Action.SUCCEEDED;
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            release();
            super.onCompleted(causeOrNull);
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            callback.failed(cause);
        }

        private void release()
        {
            chunkBuffer = Retainable.dispose(chunkBuffer);
            reset();
        }
    }
}
