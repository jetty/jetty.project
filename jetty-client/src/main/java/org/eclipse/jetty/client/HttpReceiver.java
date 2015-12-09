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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpReceiver} provides the abstract code to implement the various steps of the receive of HTTP responses.
 * <p>
 * {@link HttpReceiver} maintains a state machine that is updated when the steps of receiving a response are executed.
 * <p>
 * Subclasses must handle the transport-specific details, for example how to read from the raw socket and how to parse
 * the bytes read from the socket. Then they have to call the methods defined in this class in the following order:
 * <ol>
 * <li>{@link #responseBegin(HttpExchange)}, when the HTTP response data containing the HTTP status code
 * is available</li>
 * <li>{@link #responseHeader(HttpExchange, HttpField)}, when a HTTP field is available</li>
 * <li>{@link #responseHeaders(HttpExchange)}, when all HTTP headers are available</li>
 * <li>{@link #responseContent(HttpExchange, ByteBuffer, Callback)}, when HTTP content is available</li>
 * <li>{@link #responseSuccess(HttpExchange)}, when the response is successful</li>
 * </ol>
 * At any time, subclasses may invoke {@link #responseFailure(Throwable)} to indicate that the response has failed
 * (for example, because of I/O exceptions).
 * At any time, user threads may abort the response which will cause {@link #responseFailure(Throwable)} to be
 * invoked.
 * <p>
 * The state machine maintained by this class ensures that the response steps are not executed by an I/O thread
 * if the response has already been failed.
 *
 * @see HttpSender
 */
public abstract class HttpReceiver
{
    protected static final Logger LOG = Log.getLogger(HttpReceiver.class);

    private final AtomicReference<ResponseState> responseState = new AtomicReference<>(ResponseState.IDLE);
    private final HttpChannel channel;
    private ContentDecoder decoder;
    private Throwable failure;

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
     * <p>
     * Subclasses must have set the response status code on the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p>
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.BeginListener}s.
     *
     * @param exchange the HTTP exchange
     * @return whether the processing should continue
     */
    protected boolean responseBegin(HttpExchange exchange)
    {
        if (!updateResponseState(ResponseState.IDLE, ResponseState.TRANSIENT))
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
            if (LOG.isDebugEnabled())
                LOG.debug("Found protocol handler {}", protocolHandler);
        }
        exchange.getConversation().updateResponseListeners(handlerListener);

        if (LOG.isDebugEnabled())
            LOG.debug("Response begin {}", response);
        ResponseNotifier notifier = destination.getResponseNotifier();
        notifier.notifyBegin(conversation.getResponseListeners(), response);

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.BEGIN))
            return true;

        terminateResponse(exchange);
        return false;
    }

    /**
     * Method to be invoked when a response HTTP header is available.
     * <p>
     * Subclasses must not have added the header to the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p>
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
                    if (updateResponseState(current, ResponseState.TRANSIENT))
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

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.HEADER))
            return true;

        terminateResponse(exchange);
        return false;
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
            if (LOG.isDebugEnabled())
                LOG.debug(x);
        }
    }

    /**
     * Method to be invoked after all response HTTP headers are available.
     * <p>
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
                    if (updateResponseState(current, ResponseState.TRANSIENT))
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
            LOG.debug("Response headers {}{}{}", response, System.lineSeparator(), response.getHeaders().toString().trim());
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

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.HEADERS))
            return true;

        terminateResponse(exchange);
        return false;
    }

    /**
     * Method to be invoked when response HTTP content is available.
     * <p>
     * This method takes case of decoding the content, if necessary, and notifying {@link org.eclipse.jetty.client.api.Response.ContentListener}s.
     *
     * @param exchange the HTTP exchange
     * @param buffer the response HTTP content buffer
     * @param callback the callback
     * @return whether the processing should continue
     */
    protected boolean responseContent(HttpExchange exchange, ByteBuffer buffer, final Callback callback)
    {
        out: while (true)
        {
            ResponseState current = responseState.get();
            switch (current)
            {
                case HEADERS:
                case CONTENT:
                {
                    if (updateResponseState(current, ResponseState.TRANSIENT))
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
            LOG.debug("Response content {}{}{}", response, System.lineSeparator(), BufferUtil.toDetailString(buffer));

        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();

        ContentDecoder decoder = this.decoder;
        if (decoder == null)
        {
            notifier.notifyContent(listeners, response, buffer, callback);
        }
        else
        {
            try
            {
                List<ByteBuffer> decodeds = new ArrayList<>(2);
                while (buffer.hasRemaining())
                {
                    ByteBuffer decoded = decoder.decode(buffer);
                    if (!decoded.hasRemaining())
                        continue;
                    decodeds.add(decoded);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response content decoded ({}) {}{}{}", decoder, response, System.lineSeparator(), BufferUtil.toDetailString(decoded));
                }

                if (decodeds.isEmpty())
                {
                    callback.succeeded();
                }
                else
                {
                    int size = decodeds.size();
                    CountingCallback counter = new CountingCallback(callback, size);
                    for (int i = 0; i < size; ++i)
                        notifier.notifyContent(listeners, response, decodeds.get(i), counter);
                }
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.CONTENT))
            return true;

        terminateResponse(exchange);
        return false;
    }

    /**
     * Method to be invoked when the response is successful.
     * <p>
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
        if (!exchange.responseComplete(null))
            return false;

        responseState.set(ResponseState.IDLE);

        // Reset to be ready for another response.
        reset();

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response success {}", response);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifySuccess(listeners, response);

        // Special case for 100 Continue that cannot
        // be handled by the ContinueProtocolHandler.
        if (exchange.getResponse().getStatus() == HttpStatus.CONTINUE_100)
            return true;

        // Mark atomically the response as terminated, with
        // respect to concurrency between request and response.
        Result result = exchange.terminateResponse();
        terminateResponse(exchange, result);

        return true;
    }

    /**
     * Method to be invoked when the response is failed.
     * <p>
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
        if (exchange.responseComplete(failure))
            return abort(exchange, failure);

        return false;
    }

    private void terminateResponse(HttpExchange exchange)
    {
        Result result = exchange.terminateResponse();
        terminateResponse(exchange, result);
    }

    private void terminateResponse(HttpExchange exchange, Result result)
    {
        HttpResponse response = exchange.getResponse();

        if (LOG.isDebugEnabled())
            LOG.debug("Response complete {}", response);

        if (result != null)
        {
            result = channel.exchangeTerminating(exchange, result);
            boolean ordered = getHttpDestination().getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(exchange, result);
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}", failure == null ? "succeeded" : "failed", result);
            List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifyComplete(listeners, result);
            if (ordered)
                channel.exchangeTerminated(exchange, result);
        }
    }

    /**
     * Resets this {@link HttpReceiver} state.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to reset their own state.
     * <p>
     * Either this method or {@link #dispose()} is called.
     */
    protected void reset()
    {
        decoder = null;
    }

    /**
     * Disposes this {@link HttpReceiver} state.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to dispose their own state.
     * <p>
     * Either this method or {@link #reset()} is called.
     */
    protected void dispose()
    {
        decoder = null;
    }

    public boolean abort(HttpExchange exchange, Throwable failure)
    {
        // Update the state to avoid more response processing.
        boolean terminate;
        out: while (true)
        {
            ResponseState current = responseState.get();
            switch (current)
            {
                case FAILURE:
                {
                    return false;
                }
                default:
                {
                    if (updateResponseState(current, ResponseState.FAILURE))
                    {
                        terminate = current != ResponseState.TRANSIENT;
                        break out;
                    }
                    break;
                }
            }
        }

        this.failure = failure;

        dispose();

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response failure {} {} on {}: {}", response, exchange, getHttpChannel(), failure);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifyFailure(listeners, response, failure);

        if (terminate)
        {
            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            Result result = exchange.terminateResponse();
            terminateResponse(exchange, result);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Concurrent failure: response termination skipped, performed by helpers");
        }

        return true;
    }

    private boolean updateResponseState(ResponseState from, ResponseState to)
    {
        boolean updated = responseState.compareAndSet(from, to);
        if (!updated)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("State update failed: {} -> {}: {}", from, to, responseState.get());
        }
        return updated;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(rsp=%s,failure=%s)",
                getClass().getSimpleName(),
                hashCode(),
                responseState,
                failure);
    }

    /**
     * The request states {@link HttpReceiver} goes through when receiving a response.
     */
    private enum ResponseState
    {
        /**
         * One of the response*() methods is being executed.
         */
        TRANSIENT,
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
