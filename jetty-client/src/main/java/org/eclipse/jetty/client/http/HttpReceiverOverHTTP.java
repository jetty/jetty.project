//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;

public class HttpReceiverOverHTTP extends HttpReceiver implements HttpParser.ResponseHandler<ByteBuffer>
{
    private final HttpParser parser = new HttpParser(this);

    public HttpReceiverOverHTTP(HttpChannelOverHTTP channel)
    {
        super(channel);
    }

    @Override
    public HttpChannelOverHTTP getHttpChannel()
    {
        return (HttpChannelOverHTTP)super.getHttpChannel();
    }

    public void receive()
    {
        EndPoint endPoint = getHttpChannel().getHttpConnection().getEndPoint();
        HttpClient client = getHttpDestination().getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
        try
        {
            while (true)
            {
                int read = endPoint.fill(buffer);
                LOG.debug("Read {} bytes from {}", read, endPoint);
                if (read > 0)
                {
                    parse(buffer);
                }
                else if (read == 0)
                {
                    fillInterested();
                    break;
                }
                else
                {
                    shutdown();
                    break;
                }
            }
        }
        catch (EofException x)
        {
            LOG.ignore(x);
            failAndClose(x);
        }
        catch (Exception x)
        {
            LOG.debug(x);
            failAndClose(x);
        }
        finally
        {
            bufferPool.release(buffer);
        }
    }

    private void parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
            parser.parseNext(buffer);
    }

    private void fillInterested()
    {
        // TODO: do we need to call fillInterested() only if we are not failed (or we have an exchange) ?
        getHttpChannel().getHttpConnection().fillInterested();
    }

    private void shutdown()
    {
        // Shutting down the parser may invoke messageComplete() or earlyEOF()
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        if (!onResponseFailure(new EOFException()))
        {
            // TODO: just shutdown here, or full close ?
            getHttpChannel().getHttpConnection().close();
        }
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

        parser.setHeadResponse(exchange.getRequest().getMethod() == HttpMethod.HEAD);
        exchange.getResponse().version(version).status(status).reason(reason);

        onResponseBegin(exchange);
        return false;
    }

    @Override
    public boolean parsedHeader(HttpField field)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        onResponseHeader(exchange, field);
        return false;
    }

    @Override
    public boolean headerComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        onResponseHeaders(exchange);
        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        onResponseContent(exchange, buffer);
        return false;
    }

    @Override
    public boolean messageComplete()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false; // TODO: is it correct to return false here ?

        onResponseSuccess(exchange);
        return true;
    }

    @Override
    public void earlyEOF()
    {
        failAndClose(new EOFException());
    }

    @Override
    public void badMessage(int status, String reason)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            HttpResponse response = exchange.getResponse();
            response.status(status).reason(reason);
            failAndClose(new HttpResponseException("HTTP protocol violation: bad response", response));
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
        onResponseFailure(failure);
        getHttpChannel().getHttpConnection().close();
    }
}
