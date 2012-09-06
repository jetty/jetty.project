//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.api.CookieStore;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpReceiver implements HttpParser.ResponseHandler<ByteBuffer>
{
    private static final Logger LOG = Log.getLogger(HttpReceiver.class);

    private final AtomicBoolean complete = new AtomicBoolean();
    private final HttpParser parser = new HttpParser(this);
    private final HttpConnection connection;
    private volatile boolean failed;

    public HttpReceiver(HttpConnection connection)
    {
        this.connection = connection;
    }

    public void receive()
    {
        EndPoint endPoint = connection.getEndPoint();
        HttpClient client = connection.getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(client.getResponseBufferSize(), true);
        try
        {
            while (true)
            {
                int read = endPoint.fill(buffer);
                LOG.debug("Read {} bytes", read);
                if (read > 0)
                {
                    parser.parseNext(buffer);
                }
                else if (read == 0)
                {
                    connection.fillInterested();
                    break;
                }
                else
                {
                    parser.shutdownInput();
                    break;
                }
            }
        }
        catch (IOException x)
        {
            LOG.debug(x);
            fail(x);
        }
        finally
        {
            bufferPool.release(buffer);
        }
    }

    @Override
    public boolean startResponse(HttpVersion version, int status, String reason)
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();

        // Probe the protocol listeners
        HttpClient client = connection.getHttpClient();
        HttpResponse response = exchange.response();
        Response.Listener listener = client.lookup(status);
        if (listener == null)
        {
            listener = conversation.first().listener();
            complete.set(true);
        }
        conversation.listener(listener);

        response.version(version).status(status).reason(reason);
        LOG.debug("Receiving {}", response);

        notifyBegin(listener, response);
        return false;
    }

    @Override
    public boolean parsedHeader(HttpHeader header, String name, String value)
    {
        HttpExchange exchange = connection.getExchange();
        exchange.response().headers().put(name, value);

        switch (name.toLowerCase())
        {
            case "set-cookie":
            case "set-cookie2":
            {
                CookieStore cookieStore = connection.getHttpClient().getCookieStore();
                HttpDestination destination = connection.getDestination();
                List<HttpCookie> cookies = HttpCookieParser.parseCookies(value);
                for (HttpCookie cookie : cookies)
                    cookieStore.addCookie(destination, cookie);
                break;
            }
            default:
            {
                break;
            }
        }

        return false;
    }

    @Override
    public boolean headerComplete()
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Headers {}", response);
        notifyHeaders(conversation.listener(), response);
        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Content {}: {} bytes", response, buffer.remaining());
        notifyContent(conversation.listener(), response, buffer);
        return false;
    }

    @Override
    public boolean messageComplete(long contentLength)
    {
        if (!failed)
            success();
        return true;
    }

    protected void success()
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Received {}", response);

        parser.reset();
        failed = false;
        boolean complete = this.complete.getAndSet(false);

        exchange.responseComplete(true);
        notifySuccess(conversation.listener(), response);
        if (complete)
            conversation.complete();
    }

    protected void fail(Throwable failure)
    {
        HttpExchange exchange = connection.getExchange();

        // In case of a response error, the failure has already been notified
        // and it is possible that a further attempt to read in the receive
        // loop throws an exception that reenters here but without exchange
        if (exchange == null)
            return;

        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Failed {} {}", response, failure);

        parser.reset();
        failed = true;
        complete.set(false);

        exchange.responseComplete(false);
        notifyFailure(conversation.listener(), response, failure);
        conversation.complete();
    }

    @Override
    public boolean earlyEOF()
    {
        fail(new EOFException());
        return false;
    }

    @Override
    public void badMessage(int status, String reason)
    {
        HttpExchange exchange = connection.getExchange();
        exchange.response().status(status).reason(reason);
        fail(new HttpResponseException());
    }

    private void notifyBegin(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onBegin(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyHeaders(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyContent(Response.Listener listener, Response response, ByteBuffer buffer)
    {
        try
        {
            if (listener != null)
                listener.onContent(response, buffer);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifySuccess(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyFailure(Response.Listener listener, Response response, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(response, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void idleTimeout()
    {
        fail(new TimeoutException());
    }
}
