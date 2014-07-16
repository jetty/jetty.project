//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class HttpSenderOverHTTP extends HttpSender
{
    private final HttpGenerator generator = new HttpGenerator();

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
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        Request request = exchange.getRequest();
        ContentProvider requestContent = request.getContent();
        long contentLength = requestContent == null ? -1 : requestContent.getLength();
        String path = request.getPath();
        String query = request.getQuery();
        if (query != null)
            path += "?" + query;
        HttpGenerator.RequestInfo requestInfo = new HttpGenerator.RequestInfo(request.getVersion(), request.getHeaders(), contentLength, request.getMethod(), path);

        try
        {
            HttpClient client = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = client.getByteBufferPool();
            ByteBuffer header = bufferPool.acquire(client.getRequestBufferSize(), false);
            ByteBuffer chunk = null;

            ByteBuffer contentBuffer = null;
            boolean lastContent = false;
            if (!expects100Continue(request))
            {
                content.advance();
                contentBuffer = content.getByteBuffer();
                lastContent = content.isLast();
            }
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(requestInfo, header, chunk, contentBuffer, lastContent);
                switch (result)
                {
                    case NEED_CHUNK:
                    {
                        chunk = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case FLUSH:
                    {
                        int size = 1;
                        boolean hasChunk = chunk != null;
                        if (hasChunk)
                            ++size;
                        boolean hasContent = contentBuffer != null;
                        if (hasContent)
                            ++size;
                        ByteBuffer[] toWrite = new ByteBuffer[size];
                        ByteBuffer[] toRecycle = new ByteBuffer[hasChunk ? 2 : 1];
                        toWrite[0] = header;
                        toRecycle[0] = header;
                        if (hasChunk)
                        {
                            toWrite[1] = chunk;
                            toRecycle[1] = chunk;
                        }
                        if (hasContent)
                            toWrite[toWrite.length - 1] = contentBuffer;
                        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
                        endPoint.write(new ByteBufferRecyclerCallback(callback, bufferPool, toRecycle), toWrite);
                        return;
                    }
                    case DONE:
                    {
                        // The headers have already been generated, perhaps by a concurrent abort.
                        callback.failed(new HttpRequestException("Could not generate headers", request));
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
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        try
        {
            HttpClient client = getHttpChannel().getHttpDestination().getHttpClient();
            ByteBufferPool bufferPool = client.getByteBufferPool();
            ByteBuffer chunk = null;
            while (true)
            {
                ByteBuffer contentBuffer = content.getByteBuffer();
                boolean lastContent = content.isLast();
                HttpGenerator.Result result = generator.generateRequest(null, null, chunk, contentBuffer, lastContent);
                switch (result)
                {
                    case NEED_CHUNK:
                    {
                        chunk = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
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
                        break;
                    }
                    case DONE:
                    {
                        assert generator.isEnd();
                        callback.succeeded();
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        catch (Exception x)
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
    protected RequestState dispose()
    {
        generator.abort();
        RequestState result = super.dispose();
        shutdownOutput();
        return result;
    }

    private void shutdownOutput()
    {
        getHttpChannel().getHttpConnection().getEndPoint().shutdownOutput();
    }

    private class ByteBufferRecyclerCallback implements Callback
    {
        private final Callback callback;
        private final ByteBufferPool pool;
        private final ByteBuffer[] buffers;

        private ByteBufferRecyclerCallback(Callback callback, ByteBufferPool pool, ByteBuffer... buffers)
        {
            this.callback = callback;
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
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            for (ByteBuffer buffer : buffers)
                pool.release(buffer);
            callback.failed(x);
        }
    }
}
