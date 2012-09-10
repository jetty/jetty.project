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

import org.eclipse.jetty.client.api.CookieStore;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpReceiver implements HttpParser.ResponseHandler<ByteBuffer>
{
    private static final Logger LOG = Log.getLogger(HttpReceiver.class);

    private final HttpParser parser = new HttpParser(this);
    private final ResponseNotifier notifier = new ResponseNotifier();
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
        catch (EofException x)
        {
            LOG.ignore(x);
            fail(x);
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
        HttpResponse response = exchange.response();

        response.version(version).status(status).reason(reason);

        // Probe the protocol handlers
        Response.Listener currentListener = exchange.listener();
        Response.Listener initialListener = conversation.exchanges().peekFirst().listener();
        HttpClient client = connection.getHttpClient();
        Response.Listener handlerListener = client.lookup(exchange.request(), response);
        if (handlerListener == null)
        {
            conversation.last(exchange);
            if (currentListener == initialListener)
                conversation.listener(initialListener);
            else
                conversation.listener(new MultipleResponseListener(currentListener, initialListener));
        }
        else
        {
            if (currentListener == initialListener)
                conversation.listener(handlerListener);
            else
                conversation.listener(new MultipleResponseListener(currentListener, handlerListener));
        }

        LOG.debug("Receiving {}", response);

        notifier.notifyBegin(conversation.listener(), response);
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
        notifier.notifyHeaders(conversation.listener(), response);
        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Content {}: {} bytes", response, buffer.remaining());
        notifier.notifyContent(conversation.listener(), response, buffer);
        return false;
    }

    @Override
    public boolean messageComplete(long contentLength)
    {
        HttpExchange exchange = connection.getExchange();
        // The exchange may be null if it was failed before
        if (exchange != null && !failed)
            success();
        return true;
    }

    protected void success()
    {
        parser.reset();

        HttpExchange exchange = connection.getExchange();
        HttpResponse response = exchange.response();
        LOG.debug("Received {}", response);

        boolean exchangeComplete = exchange.responseComplete(true);

        HttpConversation conversation = exchange.conversation();
        notifier.notifySuccess(conversation.listener(), response);
        if (exchangeComplete)
        {
            Result result = new Result(exchange.request(), response);
            notifier.notifyComplete(conversation.listener(), result);
        }
    }

    protected void fail(Throwable failure)
    {
        parser.close();
        failed = true;

        HttpExchange exchange = connection.getExchange();

        // In case of a response error, the failure has already been notified
        // and it is possible that a further attempt to read in the receive
        // loop throws an exception that reenters here but without exchange
        if (exchange == null)
            return;

        HttpResponse response = exchange.response();
        LOG.debug("Failed {} {}", response, failure);

        boolean exchangeComplete = exchange.responseComplete(false);

        HttpConversation conversation = exchange.conversation();
        notifier.notifyFailure(conversation.listener(), response, failure);
        if (exchangeComplete)
        {
            Result result = new Result(exchange.request(), response, failure);
            notifier.notifyComplete(conversation.listener(), result);
        }
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
        HttpResponse response = exchange.response();
        response.status(status).reason(reason);
        fail(new HttpResponseException("HTTP protocol violation: bad response", response));
    }

    public void idleTimeout()
    {
        fail(new TimeoutException());
    }

    private class MultipleResponseListener implements Response.Listener
    {
        private final ResponseNotifier notifier = new ResponseNotifier();
        private final Response.Listener[] listeners;

        private MultipleResponseListener(Response.Listener... listeners)
        {
            this.listeners = listeners;
        }

        @Override
        public void onBegin(Response response)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifyBegin(listener, response);
            }
        }

        @Override
        public void onHeaders(Response response)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifyHeaders(listener, response);
            }
        }

        @Override
        public void onContent(Response response, ByteBuffer content)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifyContent(listener, response, content);
            }
        }

        @Override
        public void onSuccess(Response response)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifySuccess(listener, response);
            }
        }

        @Override
        public void onFailure(Response response, Throwable failure)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifyFailure(listener, response, failure);
            }
        }

        @Override
        public void onComplete(Result result)
        {
            for (Response.Listener listener : listeners)
            {
                notifier.notifyComplete(listener, result);
            }
        }
    }
}
