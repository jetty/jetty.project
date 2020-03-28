//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.http;

import java.io.EOFException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler
{
    private final HttpParser parser;
    private RetainableByteBuffer networkBuffer;
    private boolean shutdown;
    private boolean complete;

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
        HttpClient httpClient = channel.getHttpDestination().getHttpClient();
        parser = new HttpParser(this, -1, httpClient.getHttpCompliance());
        HttpClientTransport transport = httpClient.getTransport();
        if (transport instanceof HttpClientTransportOverHTTP)
        {
            HttpClientTransportOverHTTP httpTransport = (HttpClientTransportOverHTTP)transport;
            parser.setHeaderCacheSize(httpTransport.getHeaderCacheSize());
            parser.setHeaderCacheCaseSensitive(httpTransport.isHeaderCacheCaseSensitive());
        }
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

    protected ByteBuffer getResponseBuffer()
    {
        return networkBuffer == null ? null : networkBuffer.getBuffer();
    }

    @Override
    public void receive()
    {
        if (networkBuffer == null)
            acquireNetworkBuffer();
        process();
    }

    private void acquireNetworkBuffer()
    {
        networkBuffer = newNetworkBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("Acquired {}", networkBuffer);
    }

    private void reacquireNetworkBuffer()
    {
        RetainableByteBuffer currentBuffer = networkBuffer;
        if (currentBuffer == null)
            throw new IllegalStateException();

        if (currentBuffer.hasRemaining())
            throw new IllegalStateException();

        currentBuffer.release();
        networkBuffer = newNetworkBuffer();
        if (LOG.isDebugEnabled())
            LOG.debug("Reacquired {} <- {}", currentBuffer, networkBuffer);
    }

    private RetainableByteBuffer newNetworkBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        boolean direct = client.isUseInputDirectByteBuffers();
        return new RetainableByteBuffer(bufferPool, client.getResponseBufferSize(), direct);
    }

    private void releaseNetworkBuffer()
    {
        if (networkBuffer == null)
            throw new IllegalStateException();
        if (networkBuffer.hasRemaining())
            throw new IllegalStateException();
        networkBuffer.release();
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", networkBuffer);
        networkBuffer = null;
    }

    protected ByteBuffer onUpgradeFrom()
    {
        if (networkBuffer.hasRemaining())
        {
            HttpClient client = getHttpDestination().getHttpClient();
            ByteBuffer upgradeBuffer = BufferUtil.allocate(networkBuffer.remaining(), client.isUseInputDirectByteBuffers());
            BufferUtil.clearToFill(upgradeBuffer);
            BufferUtil.put(networkBuffer.getBuffer(), upgradeBuffer);
            BufferUtil.flipToFlush(upgradeBuffer, 0);
            return upgradeBuffer;
        }
        return null;
    }

    private void process()
    {
        HttpConnectionOverHTTP connection = getHttpConnection();
        EndPoint endPoint = connection.getEndPoint();
        try
        {
            while (true)
            {
                // Always parse even empty buffers to advance the parser.
                boolean stopProcessing = parse();

                // Connection may be closed or upgraded in a parser callback.
                boolean upgraded = connection != endPoint.getConnection();
                if (connection.isClosed() || upgraded)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} {}", connection, upgraded ? "upgraded" : "closed");
                    releaseNetworkBuffer();
                    return;
                }

                if (stopProcessing)
                    return;

                if (networkBuffer.getReferences() > 1)
                    reacquireNetworkBuffer();

                int read = endPoint.fill(networkBuffer.getBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes in {} from {}", read, networkBuffer, endPoint);

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
                LOG.debug("Unable to fill from endpoint {}", endPoint, x);
            networkBuffer.clear();
            releaseNetworkBuffer();
            failAndClose(x);
        }
    }

    /**
     * Parses an HTTP response in the receivers buffer.
     *
     * @return true to indicate that parsing should be interrupted (and will be resumed by another thread).
     */
    private boolean parse()
    {
        while (true)
        {
            boolean handle = parser.parseNext(networkBuffer.getBuffer());
            boolean complete = this.complete;
            this.complete = false;
            if (LOG.isDebugEnabled())
                LOG.debug("Parsed {}, remaining {} {}", handle, networkBuffer.remaining(), parser);
            if (handle)
                return true;
            if (networkBuffer.isEmpty())
                return false;
            if (complete)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Discarding unexpected content after response: {}", networkBuffer);
                networkBuffer.clear();
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
    public void startResponse(HttpVersion version, int status, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        String method = exchange.getRequest().getMethod();
        parser.setHeadResponse(HttpMethod.HEAD.is(method) ||
            (HttpMethod.CONNECT.is(method) && status == HttpStatus.OK_200));
        exchange.getResponse().version(version).status(status).reason(reason);

        responseBegin(exchange);
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

        // Store the EndPoint is case of upgrades, tunnels, etc.
        exchange.getRequest().getConversation().setAttribute(EndPoint.class.getName(), getHttpConnection().getEndPoint());
        return !responseHeaders(exchange);
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        networkBuffer.retain();
        return !responseContent(exchange, buffer, Callback.from(networkBuffer::release, this::failAndClose));
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

        if (HttpMethod.CONNECT.is(exchange.getRequest().getMethod()) && status == HttpStatus.OK_200)
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
            failAndClose(new HttpResponseException("HTTP protocol violation: bad response on " + getHttpConnection(), response, failure));
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
}
