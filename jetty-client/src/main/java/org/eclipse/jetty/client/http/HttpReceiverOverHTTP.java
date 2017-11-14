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

import java.io.EOFException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
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
    private final HttpParser parser = new HttpParser(this);
    private ByteBuffer buffer;
    private boolean shutdown;

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
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
        return buffer;
    }

    public void receive()
    {
        if (buffer == null)
            acquireBuffer();
        process();
    }

    private void acquireBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
    }

    private void releaseBuffer()
    {
        if (buffer == null)
            throw new IllegalStateException();
        if (BufferUtil.hasContent(buffer))
            throw new IllegalStateException();
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        bufferPool.release(buffer);
        buffer = null;
    }

    protected ByteBuffer onUpgradeFrom()
    {
        if (BufferUtil.hasContent(buffer))
        {
            ByteBuffer upgradeBuffer = ByteBuffer.allocate(buffer.remaining());
            upgradeBuffer.put(buffer).flip();
            return upgradeBuffer;
        }
        return null;
    }

    private void process()
    {
        try
        {
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

                int read = endPoint.fill(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes {} from {}", read, BufferUtil.toDetailString(buffer), endPoint);

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
            BufferUtil.clear(buffer);
            if (buffer != null)
                releaseBuffer();
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
            // Must parse even if the buffer is fully consumed, to allow the
            // parser to advance from asynchronous content to response complete.
            boolean handle = parser.parseNext(buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("Parsed {}, remaining {} {}", handle, buffer.remaining(), parser);
            if (handle || !buffer.hasRemaining())
                return handle;
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
        return 256;
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

        CompletableCallback callback = new CompletableCallback()
        {
            @Override
            public void resume()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Content consumed asynchronously, resuming processing");
                process();
            }

            public void abort(Throwable x)
            {
                failAndClose(x);
            }
        };
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

        boolean proceed = responseSuccess(exchange);
        if (!proceed)
            return true;

        int status = exchange.getResponse().getStatus();
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
    public void badMessage(int status, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            HttpResponse response = exchange.getResponse();
            response.status(status).reason(reason);
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
}
