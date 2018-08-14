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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NetworkBuffer;
import org.eclipse.jetty.util.BackPressure;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableCallback;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private final AtomicInteger demand = new AtomicInteger(1);
    private boolean complete;
    private final HttpParser parser;
    private NetworkBuffer networkBuffer;
    private boolean shutdown;

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

    private NetworkBuffer acquireNetworkBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        // TODO: make directness customizable.
        return new NetworkBuffer(client.getByteBufferPool(), client.getResponseBufferSize(), true);
    }

    private void releaseNetworkBuffer()
    {
        if (networkBuffer == null)
            throw new IllegalStateException();
        if (networkBuffer.hasRemaining())
            throw new IllegalStateException();
        networkBuffer.recycle();
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", networkBuffer);
        networkBuffer = null;
    }

    protected ByteBuffer onUpgradeFrom()
    {
        if (networkBuffer == null || !networkBuffer.hasRemaining())
            return null;
        ByteBuffer buffer = networkBuffer.getByteBuffer();
        ByteBuffer upgradeBuffer = ByteBuffer.allocate(buffer.remaining());
        upgradeBuffer.put(buffer).flip();
        return upgradeBuffer;
    }

    private void process()
    {
        try
        {
            HttpConnectionOverHTTP connection = getHttpConnection();
            EndPoint endPoint = connection.getEndPoint();

            if (networkBuffer == null)
                networkBuffer = acquireNetworkBuffer();

            while (true)
            {
                if (!parse())
                    return;

                // Connection may be closed or upgraded in a parser callback.
                boolean upgraded = connection != endPoint.getConnection();
                if (connection.isClosed() || upgraded)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} {}", connection, upgraded ? "upgraded" : "closed");
                    releaseNetworkBuffer();
                    return;
                }

                int read = endPoint.fill(networkBuffer.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Filled {} bytes {} from {}", read, networkBuffer, endPoint);

                if (read > 0)
                {
                    connection.addBytesIn(read);
                }
                else if (read == 0)
                {
                    releaseNetworkBuffer();
                    fillInterested();
                    return;
                }
                else
                {
                    releaseNetworkBuffer();
                    shutdown();
                    return;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            networkBuffer.clear();
            releaseNetworkBuffer();
            failAndClose(x);
        }
    }

    /**
     * Parses a HTTP response in the receivers buffer.
     *
     * @return false to indicate that parsing should be interrupted (and will be resumed by another thread).
     */
    private boolean parse()
    {
        while (true)
        {
            if (demand.get() <= 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No demand for {}", parser);
                return false;
            }

            boolean handle;
            boolean released;
            networkBuffer.retain();
            try
            {
                handle = parser.parseNext(networkBuffer.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Parsed {}, {} {}", handle, networkBuffer, parser);
            }
            finally
            {
                released = networkBuffer.unretain();
            }

            boolean complete = this.complete;
            this.complete = false;

            boolean consumed = !networkBuffer.hasRemaining();

            if (handle)
            {
                if (consumed && released)
                    releaseNetworkBuffer();
                return false;
            }

            if (consumed)
            {
                if (!released)
                    networkBuffer = acquireNetworkBuffer();
                return true;
            }

            if (complete)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Discarding unexpected content after response: {}", networkBuffer);
                networkBuffer.clear();
                return true;
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

        networkBuffer.retain();
        demand.decrementAndGet();

        ContentCallback callback = new ContentCallback(networkBuffer);
        // Do not short circuit these calls.
        boolean proceed = responseContent(exchange, buffer, callback);
        boolean async = callback.tryComplete();
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

    private class ContentCallback extends CompletableCallback implements BackPressure
    {
        private final NetworkBuffer networkBuffer;

        private ContentCallback(NetworkBuffer networkBuffer)
        {
            this.networkBuffer = networkBuffer;
        }

        @Override
        public void succeeded()
        {
            networkBuffer.unretain();
            demand.incrementAndGet();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            networkBuffer.unretain();
            super.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return networkBuffer.getInvocationType();
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

        @Override
        public void release()
        {
            networkBuffer.unretain();
        }

        @Override
        public void demand()
        {
            demand.incrementAndGet();
            if (trySucceed())
                resume();
        }
    }
}
