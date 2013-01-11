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

package org.eclipse.jetty.client;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
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

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final HttpParser parser = new HttpParser(this);
    private final HttpConnection connection;
    private final ResponseNotifier responseNotifier;
    private ContentDecoder decoder;

    public HttpReceiver(HttpConnection connection)
    {
        this.connection = connection;
        this.responseNotifier = new ResponseNotifier(connection.getHttpClient());
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
        State state = this.state.get();
        if (state == State.IDLE || state == State.RECEIVE)
            connection.fillInterested();
    }

    private void shutdown()
    {
        // Shutting down the parser may invoke messageComplete() or fail()
        parser.shutdownInput();
        State state = this.state.get();
        if (state == State.IDLE || state == State.RECEIVE)
        {
            if (!fail(new EOFException()))
                connection.close();
        }
    }

    @Override
    public boolean startResponse(HttpVersion version, int status, String reason)
    {
        if (updateState(State.IDLE, State.RECEIVE))
        {
            HttpExchange exchange = connection.getExchange();
            // The exchange may be null if it failed concurrently
            if (exchange != null)
            {
                HttpConversation conversation = exchange.getConversation();
                HttpResponse response = exchange.getResponse();

                parser.setHeadResponse(exchange.getRequest().getMethod() == HttpMethod.HEAD);
                response.version(version).status(status).reason(reason);

                // Probe the protocol handlers
                HttpExchange initialExchange = conversation.getExchanges().peekFirst();
                HttpClient client = connection.getHttpClient();
                ProtocolHandler protocolHandler = client.findProtocolHandler(exchange.getRequest(), response);
                Response.Listener handlerListener = protocolHandler == null ? null : protocolHandler.getResponseListener();
                if (handlerListener == null)
                {
                    exchange.setLast(true);
                    if (initialExchange == exchange)
                    {
                        conversation.setResponseListeners(exchange.getResponseListeners());
                    }
                    else
                    {
                        List<Response.ResponseListener> listeners = new ArrayList<>(exchange.getResponseListeners());
                        listeners.addAll(initialExchange.getResponseListeners());
                        conversation.setResponseListeners(listeners);
                    }
                }
                else
                {
                    LOG.debug("Found protocol handler {}", protocolHandler);
                    if (initialExchange == exchange)
                    {
                        conversation.setResponseListeners(Collections.<Response.ResponseListener>singletonList(handlerListener));
                    }
                    else
                    {
                        List<Response.ResponseListener> listeners = new ArrayList<>(exchange.getResponseListeners());
                        listeners.add(handlerListener);
                        conversation.setResponseListeners(listeners);
                    }
                }

                LOG.debug("Receiving {}", response);
                responseNotifier.notifyBegin(conversation.getResponseListeners(), response);
            }
        }
        return false;
    }

    @Override
    public boolean parsedHeader(HttpField field)
    {
        if (updateState(State.RECEIVE, State.RECEIVE))
        {
            HttpExchange exchange = connection.getExchange();
            // The exchange may be null if it failed concurrently
            if (exchange != null)
            {
                HttpConversation conversation = exchange.getConversation();
                HttpResponse response = exchange.getResponse();
                boolean process = responseNotifier.notifyHeader(conversation.getResponseListeners(), response, field);
                if (process)
                {
                    response.getHeaders().add(field);
                    HttpHeader fieldHeader = field.getHeader();
                    if (fieldHeader != null)
                    {
                        switch (fieldHeader)
                        {
                            case SET_COOKIE:
                            case SET_COOKIE2:
                            {
                                storeCookie(exchange.getRequest().getURI(), field);
                                break;
                            }
                            default:
                            {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private void storeCookie(URI uri, HttpField field)
    {
        try
        {
            Map<String, List<String>> header = new HashMap<>(1);
            header.put(field.getHeader().asString(), Collections.singletonList(field.getValue()));
            connection.getHttpClient().getCookieManager().put(uri, header);
        }
        catch (IOException x)
        {
            LOG.debug(x);
        }
    }

    @Override
    public boolean headerComplete()
    {
        if (updateState(State.RECEIVE, State.RECEIVE))
        {
            HttpExchange exchange = connection.getExchange();
            // The exchange may be null if it failed concurrently
            if (exchange != null)
            {
                HttpConversation conversation = exchange.getConversation();
                HttpResponse response = exchange.getResponse();
                LOG.debug("Headers {}", response);
                responseNotifier.notifyHeaders(conversation.getResponseListeners(), response);

                Enumeration<String> contentEncodings = response.getHeaders().getValues(HttpHeader.CONTENT_ENCODING.asString(), ",");
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
            }
        }
        return false;
    }

    @Override
    public boolean content(ByteBuffer buffer)
    {
        if (updateState(State.RECEIVE, State.RECEIVE))
        {
            HttpExchange exchange = connection.getExchange();
            // The exchange may be null if it failed concurrently
            if (exchange != null)
            {
                HttpConversation conversation = exchange.getConversation();
                HttpResponse response = exchange.getResponse();
                LOG.debug("Content {}: {} bytes", response, buffer.remaining());

                ContentDecoder decoder = this.decoder;
                if (decoder != null)
                {
                    buffer = decoder.decode(buffer);
                    LOG.debug("{} {}: {} bytes", decoder, response, buffer.remaining());
                }

                responseNotifier.notifyContent(conversation.getResponseListeners(), response, buffer);
            }
        }
        return false;
    }

    @Override
    public boolean messageComplete()
    {
        if (updateState(State.RECEIVE, State.RECEIVE))
            success();
        return true;
    }

    protected boolean success()
    {
        HttpExchange exchange = connection.getExchange();
        if (exchange == null)
            return false;

        AtomicMarkableReference<Result> completion = exchange.responseComplete(null);
        if (!completion.isMarked())
            return false;

        parser.reset();
        decoder = null;

        if (!updateState(State.RECEIVE, State.IDLE))
            throw new IllegalStateException();

        exchange.terminateResponse();

        HttpResponse response = exchange.getResponse();
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        responseNotifier.notifySuccess(listeners, response);
        LOG.debug("Received {}", response);

        Result result = completion.getReference();
        if (result != null)
        {
            connection.complete(exchange, !result.isFailed());

            responseNotifier.notifyComplete(listeners, result);
        }

        return true;
    }

    protected boolean fail(Throwable failure)
    {
        HttpExchange exchange = connection.getExchange();
        // In case of a response error, the failure has already been notified
        // and it is possible that a further attempt to read in the receive
        // loop throws an exception that reenters here but without exchange;
        // or, the server could just have timed out the connection.
        if (exchange == null)
            return false;

        AtomicMarkableReference<Result> completion = exchange.responseComplete(failure);
        if (!completion.isMarked())
            return false;

        parser.close();
        decoder = null;

        while (true)
        {
            State current = state.get();
            if (updateState(current, State.FAILURE))
                break;
        }

        exchange.terminateResponse();

        HttpResponse response = exchange.getResponse();
        HttpConversation conversation = exchange.getConversation();
        responseNotifier.notifyFailure(conversation.getResponseListeners(), response, failure);
        LOG.debug("Failed {} {}", response, failure);

        Result result = completion.getReference();
        if (result != null)
        {
            connection.complete(exchange, false);

            responseNotifier.notifyComplete(conversation.getResponseListeners(), result);
        }

        return true;
    }

    @Override
    public boolean earlyEOF()
    {
        failAndClose(new EOFException());
        return false;
    }

    private void failAndClose(Throwable failure)
    {
        fail(failure);
        connection.close();
    }

    @Override
    public void badMessage(int status, String reason)
    {
        HttpExchange exchange = connection.getExchange();
        HttpResponse response = exchange.getResponse();
        response.status(status).reason(reason);
        failAndClose(new HttpResponseException("HTTP protocol violation: bad response", response));
    }

    public void idleTimeout()
    {
        // If we cannot fail, it means a response arrived
        // just when we were timeout idling, so we don't close
        fail(new TimeoutException());
    }

    public boolean abort(HttpExchange exchange, Throwable cause)
    {
        return fail(cause);
    }

    private boolean updateState(State from, State to)
    {
        boolean updated = state.compareAndSet(from, to);
        if (!updated)
            LOG.debug("State update failed: {} -> {}: {}", from, to, state.get());
        return updated;
    }

    private enum State
    {
        IDLE, RECEIVE, FAILURE
    }
}
