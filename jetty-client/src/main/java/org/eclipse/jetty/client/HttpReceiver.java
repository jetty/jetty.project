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

/**
 * {@link HttpReceiver} provides the abstract code to implement the various steps of the receive of HTTP responses.
 * <p />
 * {@link HttpReceiver} maintains a state machine that is updated when the steps of receiving a response are executed.
 * <p />
 * Subclasses must handle the transport-specific details, for example how to read from the raw socket and how to parse
 * the bytes read from the socket. Then they have to call the methods defined in this class in the following order:
 * <ol>
 * <li>{@link #responseBegin(HttpExchange)}, when the HTTP response data containing the HTTP status code
 * is available</li>
 * <li>{@link #responseHeader(HttpExchange, HttpField)}, when a HTTP field is available</li>
 * <li>{@link #responseHeaders(HttpExchange)}, when all HTTP headers are available</li>
 * <li>{@link #responseContent(HttpExchange, ByteBuffer)}, when HTTP content is available; this is the only method
 * that may be invoked multiple times with different buffers containing different content</li>
 * <li>{@link #responseSuccess(HttpExchange)}, when the response is complete</li>
 * </ol>
 * At any time, subclasses may invoke {@link #responseFailure(Throwable)} to indicate that the response has failed
 * (for example, because of I/O exceptions).
 * At any time, user threads may abort the response which will cause {@link #responseFailure(Throwable)} to be
 * invoked.
 * <p />
 * The state machine maintained by this class ensures that the response steps are not executed by an I/O thread
 * if the response has already been failed.
 *
 * @see HttpSender
 */
public abstract class HttpReceiver
{
    protected static final Logger LOG = Log.getLogger(new Object(){}.getClass().getEnclosingClass());

    private final AtomicReference<ResponseState> responseState = new AtomicReference<>(ResponseState.IDLE);
    private final HttpChannel channel;
    private volatile ContentDecoder decoder;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected HttpChannel getHttpChannel()
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

    /**
     * Method to be invoked when the response status code is available.
     * <p />
     * Subclasses must have set the response status code on the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p />
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.BeginListener}s.
     *
     * @param exchange the HTTP exchange
     * @return whether the processing should continue
     */
    protected boolean responseBegin(HttpExchange exchange)
    {
        if (!updateResponseState(ResponseState.IDLE, ResponseState.BEGIN))
            return false;

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

        return true;
    }

    /**
     * Method to be invoked when a response HTTP header is available.
     * <p />
     * Subclasses must not have added the header to the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p />
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.HeaderListener}s and storing cookies.
     *
     * @param exchange the HTTP exchange
     * @param field the response HTTP field
     * @return whether the processing should continue
     */
    protected boolean responseHeader(HttpExchange exchange, HttpField field)
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
                    return false;
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

        return true;
    }

    protected void storeCookie(URI uri, HttpField field)
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

    /**
     * Method to be invoked after all response HTTP headers are available.
     * <p />
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.HeadersListener}s.
     *
     * @param exchange the HTTP exchange
     * @return whether the processing should continue
     */
    protected boolean responseHeaders(HttpExchange exchange)
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
                    return false;
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

        return true;
    }

    /**
     * Method to be invoked when response HTTP content is available.
     * <p />
     * This method takes case of decoding the content, if necessary, and notifying {@link org.eclipse.jetty.client.api.Response.ContentListener}s.
     *
     * @param exchange the HTTP exchange
     * @param buffer the response HTTP content buffer
     * @return whether the processing should continue
     */
    protected boolean responseContent(HttpExchange exchange, ByteBuffer buffer)
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
                    return false;
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

        return true;
    }

    /**
     * Method to be invoked when the response is successful.
     * <p />
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.SuccessListener}s and possibly
     * {@link org.eclipse.jetty.client.api.Response.CompleteListener}s (if the exchange is completed).
     *
     * @param exchange the HTTP exchange
     * @return whether the response was processed as successful
     */
    protected boolean responseSuccess(HttpExchange exchange)
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

    /**
     * Method to be invoked when the response is failed.
     * <p />
     * This method takes care of notifying {@link org.eclipse.jetty.client.api.Response.FailureListener}s.
     *
     * @param failure the response failure
     * @return whether the response was processed as failed
     */
    protected boolean responseFailure(Throwable failure)
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

    /**
     * Resets this {@link HttpReceiver} state.
     * <p />
     * Subclasses should override (but remember to call {@code super}) to reset their own state.
     * <p />
     * Either this method or {@link #dispose()} is called.
     */
    protected void reset()
    {
        decoder = null;
        responseState.set(ResponseState.IDLE);
    }

    /**
     * Disposes this {@link HttpReceiver} state.
     * <p />
     * Subclasses should override (but remember to call {@code super}) to dispose their own state.
     * <p />
     * Either this method or {@link #reset()} is called.
     */
    protected void dispose()
    {
        decoder = null;
        responseState.set(ResponseState.FAILURE);
    }

    public void idleTimeout()
    {
        // If we cannot fail, it means a response arrived
        // just when we were timeout idling, so we don't close
        responseFailure(new TimeoutException());
    }

    public boolean abort(Throwable cause)
    {
        return responseFailure(cause);
    }

    private boolean updateResponseState(ResponseState from, ResponseState to)
    {
        boolean updated = responseState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("State update failed: {} -> {}: {}", from, to, responseState.get());
        return updated;
    }

    /**
     * The request states {@link HttpReceiver} goes through when receiving a response.
     */
    private enum ResponseState
    {
        /**
         * The response is not yet received, the initial state
         */
        IDLE,
        /**
         * The response status code has been received
         */
        BEGIN,
        /**
         * The response headers are being received
         */
        HEADER,
        /**
         * All the response headers have been received
         */
        HEADERS,
        /**
         * The response content is being received
         */
        CONTENT,
        /**
         * The response is failed
         */
        FAILURE
    }
}
