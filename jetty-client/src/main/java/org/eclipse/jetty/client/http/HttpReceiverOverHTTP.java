//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

    public void receive()
    {
        buffer = acquireBuffer();
        process(buffer);
    }

    private ByteBuffer acquireBuffer()
    {
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        return bufferPool.acquire(client.getResponseBufferSize(), true);
    }

    private void releaseBuffer(ByteBuffer buffer)
    {
        assert this.buffer == buffer;
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        bufferPool.release(buffer);
        this.buffer = null;
    }

    private void process(ByteBuffer buffer)
    {
        try
        {
            HttpConnectionOverHTTP connection = getHttpConnection();
            EndPoint endPoint = connection.getEndPoint();
            boolean looping = false;
            while (true)
            {
                // Connection may be closed in a parser callback.
                if (connection.isClosed())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} closed", connection);
                    releaseBuffer(buffer);
                    return;
                }

                if (!looping && !parse(buffer))
                    return;

                int read = endPoint.fill(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes from {}", read, endPoint);

                if (read > 0)
                {
                    if (!parse(buffer))
                        return;
                }
                else if (read == 0)
                {
                    releaseBuffer(buffer);
                    fillInterested();
                    return;
                }
                else
                {
                    releaseBuffer(buffer);
                    shutdown();
                    return;
                }

                looping = true;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(x);
            releaseBuffer(buffer);
            failAndClose(x);
        }
    }

    /**
     * Parses a HTTP response from the given {@code buffer}.
     *
     * @param buffer the response bytes
     * @return true to indicate that the parsing may proceed (for example with another response),
     * false to indicate that the parsing should be interrupted (and will be resumed by another thread).
     */
    private boolean parse(ByteBuffer buffer)
    {
        // Must parse even if the buffer is fully consumed, to allow the
        // parser to advance from asynchronous content to response complete.
        boolean handle = parser.parseNext(buffer);
        if (LOG.isDebugEnabled())
            LOG.debug("Parsed {} - {}", handle, parser);

        if (!handle)
            return true;

        // If the parser returns true, we need to differentiate two cases:
        // A) the response is completed, so the parser is in START state;
        // B) the content is handled asynchronously, so the parser is in CONTENT state.
        return parser.isStart();
    }

    private void fillInterested()
    {
        getHttpChannel().getHttpConnection().fillInterested();
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
        parser.setHeadResponse(HttpMethod.HEAD.is(method) || HttpMethod.CONNECT.is(method));
        exchange.getResponse().version(version).status(status).reason(reason);

        responseBegin(exchange);
        return false;
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
            responseHeader(exchange, field);
    }

    @Override
    public boolean headerComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        responseHeaders(exchange);
        return false;
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
                process(HttpReceiverOverHTTP.this.buffer);
            }

            public void abort(Throwable x)
            {
                failAndClose(x);
            }
        };
        responseContent(exchange, buffer, callback);
        return callback.tryComplete();
    }

    @Override
    public boolean messageComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        responseSuccess(exchange);
        return true;
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
