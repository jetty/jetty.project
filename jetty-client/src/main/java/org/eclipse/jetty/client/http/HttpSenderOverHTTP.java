//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

public class HttpSenderOverHTTP extends HttpSender
{
    private final HttpGenerator generator = new HttpGenerator();
    private final HttpClient httpClient;
    private boolean shutdown;

    public HttpSenderOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
        httpClient = channel.getHttpDestination().getHttpClient();
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        try
        {
            new HeadersCallback(exchange, content, callback, getHttpChannel().getHttpConnection()).iterate();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            callback.failed(x);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        try
        {
            ByteBufferPool bufferPool = httpClient.getByteBufferPool();
            ByteBuffer chunk = null;
            while (true)
            {
                ByteBuffer contentBuffer = content.getByteBuffer();
                boolean lastContent = content.isLast();
                HttpGenerator.Result result = generator.generateRequest(null, null, chunk, contentBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated content ({} bytes) - {}/{}",
                            contentBuffer == null ? -1 : contentBuffer.remaining(),
                            result, generator);
                switch (result)
                {
                    case NEED_CHUNK:
                    {
                        chunk = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        callback.succeeded();
                        return;
                    }
                    case FLUSH:
                    {
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (chunk != null)
                            endPoint.write(new ByteBufferRecyclerCallback(callback, bufferPool, chunk), chunk, contentBuffer);
                        else
                            endPoint.write(callback, contentBuffer);
                        return;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        if (lastContent)
                            break;
                        callback.succeeded();
                        return;
                    }
                    case DONE:
                    {
                        callback.succeeded();
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException(result.toString());
                    }
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            callback.failed(x);
        }
    }

    @Override
    protected void sendTrailers(HttpExchange exchange, Callback callback)
    {
        try
        {
            new TrailersCallback(callback).iterate();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            callback.failed(x);
        }
    }

    @Override
    protected void reset()
    {
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
        private final HttpExchange exchange;
        private final Callback callback;
        private final MetaData.Request metaData;
        private final HttpConnectionOverHTTP httpConnectionOverHTTP;
        private ByteBuffer headerBuffer;
        private ByteBuffer chunkBuffer;
        private ByteBuffer contentBuffer;
        private boolean lastContent;
        private boolean generated;

        public HeadersCallback(HttpExchange exchange, HttpContent content, Callback callback, HttpConnectionOverHTTP httpConnectionOverHTTP)
        {
            super(false);
            this.exchange = exchange;
            this.callback = callback;
            this.httpConnectionOverHTTP = httpConnectionOverHTTP;

            HttpRequest request = exchange.getRequest();
            ContentProvider requestContent = request.getContent();
            long contentLength = requestContent == null ? -1 : requestContent.getLength();
            String path = request.getPath();
            String query = request.getQuery();
            if (query != null)
                path += "?" + query;
            metaData = new MetaData.Request(request.getMethod(), new HttpURI(path), request.getVersion(), request.getHeaders(), contentLength);
            metaData.setTrailerSupplier(request.getTrailers());

            if (!expects100Continue(request))
            {
                content.advance();
                contentBuffer = content.getByteBuffer();
                lastContent = content.isLast();
            }
        }

        @Override
        protected Action process() throws Exception
        {
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(metaData, headerBuffer, chunkBuffer, contentBuffer, lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated headers ({} bytes), chunk ({} bytes), content ({} bytes) - {}/{}",
                            headerBuffer == null ? -1 : headerBuffer.remaining(),
                            chunkBuffer == null ? -1 : chunkBuffer.remaining(),
                            contentBuffer == null ? -1 : contentBuffer.remaining(),
                            result, generator);
                switch (result)
                {
                    case NEED_HEADER:
                    {
                        headerBuffer = httpClient.getByteBufferPool().acquire(httpClient.getRequestBufferSize(), false);
                        break;
                    }
                    case NEED_CHUNK:
                    {
                        chunkBuffer = httpClient.getByteBufferPool().acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case NEED_CHUNK_TRAILER:
                    {
                        return Action.SUCCEEDED;
                    }
                    case FLUSH:
                    {
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        if (headerBuffer == null)
                            headerBuffer = BufferUtil.EMPTY_BUFFER;
                        if (chunkBuffer == null)
                            chunkBuffer = BufferUtil.EMPTY_BUFFER;
                        if (contentBuffer == null)
                            contentBuffer = BufferUtil.EMPTY_BUFFER;

                        httpConnectionOverHTTP.addBytesOut( BufferUtil.length(headerBuffer)
                                                          + BufferUtil.length(chunkBuffer)
                                                          + BufferUtil.length(contentBuffer));
                        
                        endPoint.write(this, headerBuffer, chunkBuffer, contentBuffer);
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
        public void succeeded()
        {
            release();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            release();
            callback.failed(x);
            super.failed(x);
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
            callback.succeeded();
        }

        private void release()
        {
            ByteBufferPool bufferPool = httpClient.getByteBufferPool();
            if (headerBuffer != BufferUtil.EMPTY_BUFFER)
                bufferPool.release(headerBuffer);
            headerBuffer = null;
            if (chunkBuffer != BufferUtil.EMPTY_BUFFER)
                bufferPool.release(chunkBuffer);
            chunkBuffer = null;
            contentBuffer = null;
        }
    }

    private class TrailersCallback extends IteratingCallback
    {
        private final Callback callback;
        private ByteBuffer chunkBuffer;

        public TrailersCallback(Callback callback)
        {
            this.callback = callback;
        }

        @Override
        protected Action process() throws Throwable
        {
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(null, null, chunkBuffer, null, true);
                if (LOG.isDebugEnabled())
                    LOG.debug("Generated trailers {}/{}", result, generator);
                switch (result)
                {
                    case NEED_CHUNK_TRAILER:
                    {
                        chunkBuffer = httpClient.getByteBufferPool().acquire(httpClient.getRequestBufferSize(), false);
                        break;
                    }
                    case FLUSH:
                    {
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        endPoint.write(this, chunkBuffer);
                        return Action.SCHEDULED;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        return Action.SUCCEEDED;
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
        public void succeeded()
        {
            release();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            release();
            callback.failed(x);
            super.failed(x);
        }

        @Override
        protected void onCompleteSuccess()
        {
            super.onCompleteSuccess();
            callback.succeeded();
        }

        private void release()
        {
            httpClient.getByteBufferPool().release(chunkBuffer);
            chunkBuffer = null;
        }
    }

    private class ByteBufferRecyclerCallback extends Callback.Nested
    {
        private final ByteBufferPool pool;
        private final ByteBuffer[] buffers;

        private ByteBufferRecyclerCallback(Callback callback, ByteBufferPool pool, ByteBuffer... buffers)
        {
            super(callback);
            this.pool = pool;
            this.buffers = buffers;
        }

        @Override
        public void succeeded()
        {
            for (ByteBuffer buffer : buffers)
            {
                assert !buffer.hasRemaining();
                pool.release(buffer);
            }
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            for (ByteBuffer buffer : buffers)
                pool.release(buffer);
            super.failed(x);
        }
    }
}
