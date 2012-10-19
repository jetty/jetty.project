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
import java.nio.ByteBuffer;
import java.util.Enumeration;
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
    private final HttpConnection connection;
    private final ResponseNotifier notifier;
    private ContentDecoder decoder;
    private State state = State.IDLE;

    public HttpReceiver(HttpConnection connection)
    {
        this.connection = connection;
        this.notifier = new ResponseNotifier(connection.getHttpClient());
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
                LOG.debug("Read {} bytes from {}", read, connection);
                if (read > 0)
                {
                    while (buffer.hasRemaining())
                        parser.parseNext(buffer);
                }
                else if (read == 0)
                {
                    connection.fillInterested();
                    break;
                }
                else
                {
                    // Shutting down the parser may invoke messageComplete() or fail()
                    parser.shutdownInput();
                    if (state == State.IDLE || state == State.RECEIVE)
                        fail(new EOFException());
                    break;
                }
            }
        }
        catch (EofException x)
        {
            LOG.ignore(x);
            fail(x);
        }
        catch (Exception x)
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
        state = State.RECEIVE;

        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();

        response.version(version).status(status).reason(reason);

        // Probe the protocol handlers
        Response.Listener currentListener = exchange.listener();
        Response.Listener initialListener = conversation.exchanges().peekFirst().listener();
        HttpClient client = connection.getHttpClient();
        ProtocolHandler protocolHandler = client.findProtocolHandler(exchange.request(), response);
        Response.Listener handlerListener = protocolHandler == null ? null : protocolHandler.getResponseListener();
        if (handlerListener == null)
        {
            conversation.last(exchange);
            if (currentListener == initialListener)
                conversation.listener(initialListener);
            else
                conversation.listener(new DoubleResponseListener(currentListener, initialListener));
        }
        else
        {
            LOG.debug("Found protocol handler {}", protocolHandler);
            if (currentListener == initialListener)
                conversation.listener(handlerListener);
            else
                conversation.listener(new DoubleResponseListener(currentListener, handlerListener));
        }

        LOG.debug("Receiving {}", response);

        notifier.notifyBegin(conversation.listener(), response);
        return false;
    }

    @Override
    public boolean parsedHeader(HttpHeader header, String name, String value)
    {
        HttpExchange exchange = connection.getExchange();
        exchange.response().headers().add(name, value);

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

        Enumeration<String> contentEncodings = response.headers().getValues(HttpHeader.CONTENT_ENCODING.asString(), ",");
        if (contentEncodings != null)
        {
            for (ContentDecoder.Factory factory : connection.getHttpClient().getContentDecoderFactories())
            {
                while (contentEncodings.hasMoreElements())
                {
                    if (factory.getEncoding().equalsIgnoreCase(contentEncodings.nextElement()))
                    {
                        this.decoder = factory.newContentDecoder();
                        break;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        HttpExchange exchange = connection.getExchange();
        HttpConversation conversation = exchange.conversation();
        HttpResponse response = exchange.response();
        LOG.debug("Content {}: {} bytes", response, buffer.remaining());

        ContentDecoder decoder = this.decoder;
        if (decoder != null)
        {
            buffer = decoder.decode(buffer);
            LOG.debug("{} {}: {} bytes", decoder, response, buffer.remaining());
        }

        notifier.notifyContent(conversation.listener(), response, buffer);
        return false;
    }

    @Override
    public boolean messageComplete()
    {
        HttpExchange exchange = connection.getExchange();
        // The exchange may be null if it was failed before
        if (exchange != null && state == State.RECEIVE)
            success();
        return true;
    }

    protected void success()
    {
        parser.reset();
        state = State.SUCCESS;

        HttpExchange exchange = connection.getExchange();
        HttpResponse response = exchange.response();
        LOG.debug("Received {}", response);

        Result result = exchange.responseComplete(null);

        HttpConversation conversation = exchange.conversation();
        notifier.notifySuccess(conversation.listener(), response);
        if (result != null)
        {
            notifier.notifyComplete(conversation.listener(), result);
            reset();
        }
    }

    protected void fail(Throwable failure)
    {
        HttpExchange exchange = connection.getExchange();
        // In case of a response error, the failure has already been notified
        // and it is possible that a further attempt to read in the receive
        // loop throws an exception that reenters here but without exchange;
        // or, the server could just have timed out the connection.
        if (exchange == null)
            return;

        parser.close();
        state = State.FAILURE;

        HttpResponse response = exchange.response();
        LOG.debug("Failed {} {}", response, failure);

        Result result = exchange.responseComplete(failure);

        HttpConversation conversation = exchange.conversation();
        notifier.notifyFailure(conversation.listener(), response, failure);
        if (result != null)
        {
            notifier.notifyComplete(conversation.listener(), result);
            reset();
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

    private void reset()
    {
        decoder = null;
        state = State.IDLE;
    }

    private class DoubleResponseListener implements Response.Listener
    {
        private final Response.Listener listener1;
        private final Response.Listener listener2;

        private DoubleResponseListener(Response.Listener listener1, Response.Listener listener2)
        {
            this.listener1 = listener1;
            this.listener2 = listener2;
        }

        @Override
        public void onBegin(Response response)
        {
            notifier.notifyBegin(listener1, response);
            notifier.notifyBegin(listener2, response);
        }

        @Override
        public void onHeaders(Response response)
        {
            notifier.notifyHeaders(listener1, response);
            notifier.notifyHeaders(listener2, response);
        }

        @Override
        public void onContent(Response response, ByteBuffer content)
        {
            notifier.notifyContent(listener1, response, content);
            notifier.notifyContent(listener2, response, content);
        }

        @Override
        public void onSuccess(Response response)
        {
            notifier.notifySuccess(listener1, response);
            notifier.notifySuccess(listener2, response);
        }

        @Override
        public void onFailure(Response response, Throwable failure)
        {
            notifier.notifyFailure(listener1, response, failure);
            notifier.notifyFailure(listener2, response, failure);
        }

        @Override
        public void onComplete(Result result)
        {
            notifier.notifyComplete(listener1, result);
            notifier.notifyComplete(listener2, result);
        }
    }

    private enum State
    {
        IDLE, RECEIVE, SUCCESS, FAILURE
    }
}
