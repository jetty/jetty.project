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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpReceiver
{
    protected static final Logger LOG = Log.getLogger(new Object(){}.getClass().getEnclosingClass());

    private final AtomicReference<ResponseState> responseState = new AtomicReference<>(ResponseState.IDLE);
    private final HttpChannel channel;
    private volatile ContentDecoder decoder;

    public HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
    }

    public HttpChannel getHttpChannel()
    {
        return channel;
    }

    protected HttpExchange getHttpExchange()
    {
        return channel.getHttpExchange();
    }

    protected HttpDestination getHttpDestination()
    {
        return channel.getHttpDestination();
    }

    protected void onResponseBegin(HttpExchange exchange)
    {
        if (!updateResponseState(ResponseState.IDLE, ResponseState.BEGIN))
            return;

        HttpConversation conversation = exchange.getConversation();
        HttpResponse response = exchange.getResponse();
        // Probe the protocol handlers
        HttpDestination destination = getHttpDestination();
        HttpClient client = destination.getHttpClient();
        ProtocolHandler protocolHandler = client.findProtocolHandler(exchange.getRequest(), response);
        Response.Listener handlerListener = null;
        if (protocolHandler != null)
        {
            handlerListener = protocolHandler.getResponseListener();
            LOG.debug("Found protocol handler {}", protocolHandler);
        }
        exchange.getConversation().updateResponseListeners(handlerListener);

        LOG.debug("Response begin {}", response);
        ResponseNotifier notifier = destination.getResponseNotifier();
        notifier.notifyBegin(conversation.getResponseListeners(), response);
    }

    protected void onResponseHeader(HttpExchange exchange, HttpField field)
    {
        out: while (true)
        {
            ResponseState current = responseState.get();
            switch (current)
            {
                case BEGIN:
                case HEADER:
                {
                    if (updateResponseState(current, ResponseState.HEADER))
                        break out;
                    break;
                }
                default:
                {
                    return;
                }
            }
        }

        HttpResponse response = exchange.getResponse();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        boolean process = notifier.notifyHeader(exchange.getConversation().getResponseListeners(), response, field);
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

    private void storeCookie(URI uri, HttpField field)
    {
        try
        {
            String value = field.getValue();
            if (value != null)
            {
                Map<String, List<String>> header = new HashMap<>(1);
                header.put(field.getHeader().asString(), Collections.singletonList(value));
                getHttpDestination().getHttpClient().getCookieManager().put(uri, header);
            }
        }
        catch (IOException x)
        {
            LOG.debug(x);
        }
    }

    protected void onResponseHeaders(HttpExchange exchange)
    {
        out: while (true)
        {
            ResponseState current = responseState.get();
            switch (current)
            {
                case BEGIN:
                case HEADER:
                {
                    if (updateResponseState(current, ResponseState.HEADERS))
                        break out;
                    break;
                }
                default:
                {
                    return;
                }
            }
        }

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response headers {}{}{}", response, System.getProperty("line.separator"), response.getHeaders().toString().trim());
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifyHeaders(exchange.getConversation().getResponseListeners(), response);

        Enumeration<String> contentEncodings = response.getHeaders().getValues(HttpHeader.CONTENT_ENCODING.asString(), ",");
        if (contentEncodings != null)
        {
            for (ContentDecoder.Factory factory : getHttpDestination().getHttpClient().getContentDecoderFactories())
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

    protected void onResponseContent(HttpExchange exchange, ByteBuffer buffer)
    {
        out: while (true)
        {
            ResponseState current = responseState.get();
            switch (current)
            {
                case HEADERS:
                case CONTENT:
                {
                    if (updateResponseState(current, ResponseState.CONTENT))
                        break out;
                    break;
                }
                default:
                {
                    return;
                }
            }
        }

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response content {}{}{}", response, System.getProperty("line.separator"), BufferUtil.toDetailString(buffer));

        ContentDecoder decoder = this.decoder;
        if (decoder != null)
        {
            buffer = decoder.decode(buffer);
            if (LOG.isDebugEnabled())
                LOG.debug("Response content decoded ({}) {}{}{}", decoder, response, System.getProperty("line.separator"), BufferUtil.toDetailString(buffer));
        }

        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifyContent(exchange.getConversation().getResponseListeners(), response, buffer);
    }

    protected boolean onResponseSuccess(HttpExchange exchange)
    {
        // Mark atomically the response as completed, with respect
        // to concurrency between response success and response failure.
        boolean completed = exchange.responseComplete();
        if (!completed)
            return false;

        // Reset to be ready for another response
        reset();

        // Mark atomically the response as terminated and succeeded,
        // with respect to concurrency between request and response.
        // If there is a non-null result, then both sender and
        // receiver are reset and ready to be reused, and the
        // connection closed/pooled (depending on the transport).
        Result result = exchange.terminateResponse(null);

        HttpResponse response = exchange.getResponse();
        LOG.debug("Response success {}", response);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifySuccess(listeners, response);

        if (result != null)
        {
            boolean ordered = getHttpDestination().getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(result);
            LOG.debug("Request/Response complete {}", response);
            notifier.notifyComplete(listeners, result);
            if (ordered)
                channel.exchangeTerminated(result);
        }

        return true;
    }

    protected boolean onResponseFailure(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        // In case of a response error, the failure has already been notified
        // and it is possible that a further attempt to read in the receive
        // loop throws an exception that reenters here but without exchange;
        // or, the server could just have timed out the connection.
        if (exchange == null)
            return false;

        // Mark atomically the response as completed, with respect
        // to concurrency between response success and response failure.
        boolean completed = exchange.responseComplete();
        if (!completed)
            return false;

        // Dispose to avoid further responses
        dispose();

        // Mark atomically the response as terminated and failed,
        // with respect to concurrency between request and response.
        Result result = exchange.terminateResponse(failure);

        HttpResponse response = exchange.getResponse();
        LOG.debug("Response failure {} {}", response, failure);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifyFailure(listeners, response, failure);

        if (result != null)
        {
            boolean ordered = getHttpDestination().getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(result);
            notifier.notifyComplete(listeners, result);
            if (ordered)
                channel.exchangeTerminated(result);
        }

        return true;
    }

    protected void reset()
    {
        responseState.set(ResponseState.IDLE);
        decoder = null;
    }

    protected void dispose()
    {
        decoder = null;
    }

    public void idleTimeout()
    {
        // If we cannot fail, it means a response arrived
        // just when we were timeout idling, so we don't close
        onResponseFailure(new TimeoutException());
    }

    public boolean abort(Throwable cause)
    {
        return onResponseFailure(cause);
    }

    private boolean updateResponseState(ResponseState from, ResponseState to)
    {
        boolean updated = responseState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("State update failed: {} -> {}: {}", from, to, responseState.get());
        return updated;
    }

    private enum ResponseState
    {
        IDLE, BEGIN, HEADER, HEADERS, CONTENT, FAILURE
    }
}
