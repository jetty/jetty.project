//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableCallback;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private final Queue<NetworkBuffer> pendingBuffers = new ConcurrentLinkedDeque<>();
    private final Object lock = new Object();
    private final HttpParser parser;
    private NetworkBuffer currentBuffer;
    private boolean shutdown;
    private boolean complete;
    private long demand = 1;
    private boolean stalled;

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
        parser = new HttpParser(this, -1, channel.getHttpDestination().getHttpClient().getHttpCompliance());
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    private HttpConnectionOverHTTP getHttpConnection()
    {
        return getHttpChannel().getHttpConnection();
    }

    // TODO: restore or remove this method.
    protected ByteBuffer getResponseBuffer()
    {
        return null;
    }

    public void receive()
    {
        process();
    }

    private NetworkBuffer acquireBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
        return new NetworkBuffer(buffer);
    }

    private void releaseBuffer()
    {
        if (currentBuffer == null)
            throw new IllegalStateException();
        if (currentBuffer.hasRemaining())
            throw new IllegalStateException();
        releaseBuffer(currentBuffer);
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", currentBuffer);
        currentBuffer = null;
    }

    private void releaseBuffer(NetworkBuffer networkBuffer)
    {
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        bufferPool.release(networkBuffer.buffer);
    }

    protected ByteBuffer onUpgradeFrom()
    {
        if (currentBuffer == null || !currentBuffer.hasRemaining())
            return null;
        ByteBuffer buffer = currentBuffer.buffer;
        ByteBuffer upgradeBuffer = ByteBuffer.allocate(buffer.remaining());
        upgradeBuffer.put(buffer).flip();
        return upgradeBuffer;
    }

    private void process()
    {
        try
        {
            if (currentBuffer == null)
                currentBuffer = acquireBuffer();

            HttpConnectionOverHTTP connection = getHttpConnection();
            EndPoint endPoint = connection.getEndPoint();

            while (true)
            {
                boolean upgraded = connection != endPoint.getConnection();

                // Connection may be closed or upgraded in a parser callback.
                if (connection.isClosed() || upgraded)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} {}", connection, upgraded ? "upgraded" : "closed");
                    releaseBuffer();
                    return;
                }

                if (parse())
                    return;

                int read = endPoint.fill(currentBuffer.buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes {} from {}", read, currentBuffer, endPoint);

                if (read > 0)
                {
                    connection.addBytesIn(read);
                    if (parse())
                        return;
                }
                else if (read == 0)
                {
                    releaseBuffer();
                    fillInterested();
                    return;
                }
                else
                {
                    releaseBuffer();
                    shutdown();
                    return;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            if (currentBuffer != null)
            {
                currentBuffer.clear();
                releaseBuffer();
            }
            failAndClose(x);
        }
    }

    /**
     * Parses a HTTP response in the receivers buffer.
     *
     * @return true to indicate that parsing should be interrupted (and will be resumed by another thread).
     */
    private boolean parse()
    {
        while (true)
        {
            synchronized (lock)
            {
                if (demand <= 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No demand for {}", parser);
                    stalled = true;
                    return true;
                }
            }

            boolean handle = parser.parseNext(currentBuffer.buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("Parsed {}, remaining {} {}", handle, currentBuffer.getRemaining(), parser);

            boolean complete = this.complete;
            this.complete = false;

            if (handle)
                return true;

            if (!currentBuffer.hasRemaining())
            {
                if (!currentBuffer.tryRelease())
                    currentBuffer = acquireBuffer();
                return false;
            }

            if (complete)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Discarding unexpected content after response: {}", currentBuffer);
                currentBuffer.clear();
                return false;
            }
        }
    }

    protected void fillInterested()
    {
        getHttpConnection().fillInterested();
    }

    private void shutdown()
    {
        // Mark this receiver as shutdown, so that we can
        // close the connection when the exchange terminates.
        // We cannot close the connection from here because
        // the request may still be in process.
        shutdown = true;

        // Shutting down the parser may invoke messageComplete() or earlyEOF().
        // In case of content delimited by EOF, without a Connection: close
        // header, the connection will be closed at exchange termination
        // thanks to the flag we have set above.
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
    }

    protected boolean isShutdown()
    {
        return shutdown;
    }

    @Override
    public int getHeaderCacheSize()
    {
        // TODO get from configuration
        return 4096;
    }

    @Override
    public boolean startResponse(HttpVersion version, int status, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        String method = exchange.getRequest().getMethod();
        parser.setHeadResponse(HttpMethod.HEAD.is(method) ||
                (HttpMethod.CONNECT.is(method) && status == HttpStatus.OK_200));
        exchange.getResponse().version(version).status(status).reason(reason);

        return !responseBegin(exchange);
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        responseHeader(exchange, field);
    }

    @Override
    public boolean headerComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        return !responseHeaders(exchange);
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        currentBuffer.retain();

        synchronized (lock)
        {
            --demand;
        }

        Content content = new Content(currentBuffer, buffer);
        // Do not short circuit these calls.
        boolean proceed = responseContent(exchange, buffer, content);
        boolean async = content.tryComplete();
        return !proceed || async;
    }

    @Override
    public boolean contentComplete()
    {
        return false;
    }

    @Override
    public void parsedTrailer(HttpField trailer)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        exchange.getResponse().trailer(trailer);
    }

    @Override
    public boolean messageComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        int status = exchange.getResponse().getStatus();

        if (status != HttpStatus.CONTINUE_100)
            complete = true;

        boolean proceed = responseSuccess(exchange);
        if (!proceed)
            return true;

        if (status == HttpStatus.SWITCHING_PROTOCOLS_101)
            return true;

        if (HttpMethod.CONNECT.is(exchange.getRequest().getMethod()) &&
                status == HttpStatus.OK_200)
            return true;

        return false;
    }

    @Override
    public void earlyEOF()
    {
        HttpExchange exchange = getHttpExchange();
        HttpConnectionOverHTTP connection = getHttpConnection();
        if (exchange == null)
            connection.close();
        else
            failAndClose(new EOFException(String.valueOf(connection)));
    }

    @Override
    public void badMessage(BadMessageException failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            HttpResponse response = exchange.getResponse();
            response.status(failure.getCode()).reason(failure.getReason());
            failAndClose(new HttpResponseException("HTTP protocol violation: bad response on " + getHttpConnection(), response));
        }
    }

    @Override
    protected void reset()
    {
        super.reset();
        parser.reset();
    }

    @Override
    protected void dispose()
    {
        super.dispose();
        parser.close();
    }

    private void failAndClose(Throwable failure)
    {
        if (responseFailure(failure))
            getHttpConnection().close(failure);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", super.toString(), parser);
    }

    private class NetworkBuffer
    {
        private final ByteBuffer buffer;
        private int refCount;

        private NetworkBuffer(ByteBuffer buffer)
        {
            this.buffer = buffer;
        }

        private boolean hasRemaining()
        {
            return buffer.hasRemaining();
        }

        private int getRemaining()
        {
            return buffer.remaining();
        }

        private void clear()
        {
            BufferUtil.clear(buffer);
        }

        private void retain()
        {
            synchronized (this)
            {
                ++refCount;
            }
        }

        private void release()
        {
            boolean release = false;
            synchronized (this)
            {
                --refCount;
                if (refCount == 0 && !hasRemaining())
                    release = pendingBuffers.remove(this);
            }
            if (release)
            {
                releaseBuffer(this);
                if (LOG.isDebugEnabled())
                    LOG.debug("Released retained {}", this);
            }
        }

        private boolean tryRelease()
        {
            synchronized (this)
            {
                if (refCount > 0)
                {
                    pendingBuffers.add(currentBuffer);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Retained {}", this);
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), buffer);
        }
    }

    private class Content extends CompletableCallback implements Response.Content
    {
        private final NetworkBuffer networkBuffer;
        private final ByteBuffer contentBuffer;

        private Content(NetworkBuffer networkBuffer, ByteBuffer contentBuffer)
        {
            this.networkBuffer = networkBuffer;
            this.contentBuffer = contentBuffer;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return contentBuffer;
        }

        @Override
        public void demand(long n)
        {
            if (n <= 0)
                throw new IllegalArgumentException("Invalid demand " + n);
            boolean proceed = false;
            synchronized (lock)
            {
                demand = cappedAdd(demand, n);
                if (stalled)
                {
                    stalled = false;
                    proceed = true;
                }
            }
            if (proceed)
                process();
        }

        @Override
        public void release()
        {
            networkBuffer.release();
        }

        @Override
        public void succeed()
        {
            succeeded();
        }

        @Override
        public void succeeded()
        {
            release();
            demand(1);
            super.succeeded();
        }

        @Override
        public void fail(Throwable failure)
        {
            failed(failure);
        }

        @Override
        public void resume()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Content consumed asynchronously, resuming processing");
            process();
        }

        @Override
        public void abort(Throwable x)
        {
            failAndClose(x);
        }
    }

    private static long cappedAdd(long x, long y) {
        long r = x + y;
        // Overflow ?
        if (((x ^ r) & (y ^ r)) < 0) {
            return Long.MAX_VALUE;
        }
        return r;
    }
}
