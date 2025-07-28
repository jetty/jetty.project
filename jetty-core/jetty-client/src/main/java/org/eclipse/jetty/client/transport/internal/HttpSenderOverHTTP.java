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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpSenderOverHTTP extends HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSenderOverHTTP.class);

    private final IteratingCallback headersCallback = new HeadersCallback();
    private final IteratingCallback contentCallback = new ContentCallback();
    private final HttpGenerator generator = new HttpGenerator();
    private MetaData.Request metaData;
    private ByteBuffer contentByteBuffer;
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

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        try
        {
            this.contentByteBuffer = contentBuffer;
            this.lastContent = lastContent;
            this.callback = callback;
            HttpRequest request = exchange.getRequest();
            Content.Source requestContent = request.getBody();
            long contentLength = requestContent == null ? -1 : requestContent.getLength();
            // URI validations already performed by using the java.net.URI class.
            HttpURI uri = HttpURI.from(null, null, -1, request.getPath(), request.getQuery(), null);
            metaData = new MetaData.Request(request.getMethod(), uri, request.getVersion(), request.getHeaders(), contentLength, request.getTrailersSupplier());
            if (LOG.isDebugEnabled())
                LOG.debug("Sending headers with content {} last={} for {}", BufferUtil.toDetailString(contentBuffer), lastContent, exchange.getRequest());
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
    protected void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        try
        {
            this.contentByteBuffer = contentBuffer;
            this.lastContent = lastContent;
            this.callback = callback;
            if (LOG.isDebugEnabled())
                LOG.debug("Sending content {} last={} for {}", BufferUtil.toDetailString(contentBuffer), lastContent, exchange.getRequest());
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
        private RetainableByteBuffer headerBuffer;
        private RetainableByteBuffer chunkBuffer;
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
            ByteBufferPool bufferPool = httpClient.getByteBufferPool();
            int requestHeadersSize = httpClient.getRequestBufferSize();
            boolean useDirectByteBuffers = httpClient.isUseOutputDirectByteBuffers();
            while (true)
            {
                ByteBuffer headerByteBuffer = headerBuffer == null ? null : headerBuffer.getByteBuffer();
                ByteBuffer chunkByteBuffer = chunkBuffer == null ? null : chunkBuffer.getByteBuffer();
                HttpGenerator.Result result = generator.generateRequest(metaData, headerByteBuffer, chunkByteBuffer, contentByteBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated headers ({} bytes), chunk ({} bytes), content ({} bytes) - {}/{} for {}",
                        headerByteBuffer == null ? -1 : headerByteBuffer.remaining(),
                        chunkByteBuffer == null ? -1 : chunkByteBuffer.remaining(),
                        contentByteBuffer == null ? -1 : contentByteBuffer.remaining(),
                        result, generator, exchange.getRequest());
                switch (result)
                {
                    case NEED_HEADER:
                    {
                        generator.setMaxHeaderBytes(getHttpChannel().getHttpDestination().getHttpClient().getMaxRequestHeadersSize());
                        headerBuffer = bufferPool.acquire(requestHeadersSize, useDirectByteBuffers);
                        break;
                    }
                    case HEADER_OVERFLOW:
                    {
                        int maxRequestHeadersSize = httpClient.getMaxRequestHeadersSize();
                        if (maxRequestHeadersSize > requestHeadersSize)
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
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (headerByteBuffer == null)
                            headerByteBuffer = BufferUtil.EMPTY_BUFFER;
                        if (chunkByteBuffer == null)
                            chunkByteBuffer = BufferUtil.EMPTY_BUFFER;
                        if (contentByteBuffer == null)
                            contentByteBuffer = BufferUtil.EMPTY_BUFFER;
                        long bytes = headerByteBuffer.remaining() + chunkByteBuffer.remaining() + contentByteBuffer.remaining();
                        getHttpChannel().getHttpConnection().addBytesOut(bytes);
                        endPoint.write(this, headerByteBuffer, chunkByteBuffer, contentByteBuffer);
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
            release();
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            super.onCompleteFailure(cause);
            release();
            callback.failed(cause);
        }

        private void release()
        {
            if (headerBuffer != null)
                headerBuffer.release();
            headerBuffer = null;
            if (chunkBuffer != null)
                chunkBuffer.release();
            chunkBuffer = null;
            contentByteBuffer = null;
        }
    }

    private class ContentCallback extends IteratingCallback
    {
        private RetainableByteBuffer chunkBuffer;

        public ContentCallback()
        {
            super(false);
        }

        @Override
        protected Action process() throws Exception
        {
            HttpClient httpClient = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = httpClient.getByteBufferPool();
            boolean useDirectByteBuffers = httpClient.isUseOutputDirectByteBuffers();
            while (true)
            {
                ByteBuffer chunkByteBuffer = chunkBuffer == null ? null : chunkBuffer.getByteBuffer();
                HttpGenerator.Result result = generator.generateRequest(null, null, chunkByteBuffer, contentByteBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated content ({} bytes, last={}) - {}/{}",
                        contentByteBuffer == null ? -1 : contentByteBuffer.remaining(),
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
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (chunkByteBuffer != null)
                            endPoint.write(this, chunkByteBuffer, contentByteBuffer);
                        else
                            endPoint.write(this, contentByteBuffer);
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
                        release();
                        callback.succeeded();
                        return Action.IDLE;
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            release();
            callback.failed(cause);
        }

        private void release()
        {
            if (chunkBuffer != null)
                chunkBuffer.release();
            chunkBuffer = null;
            contentByteBuffer = null;
        }
    }
}
