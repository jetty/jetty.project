//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.LongUnaryOperator;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.component.Destroyable;
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
 * <li>{@link #responseHeader(HttpExchange, HttpField)}, when an HTTP field is available</li>
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
    private ContentListeners contentListeners;
    private Decoder decoder;
    private Throwable failure;
    private long demand;
    private boolean stalled;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected HttpChannel getHttpChannel()
    {
        return channel;
    }

    void demand(long n)
    {
        if (n <= 0)
            throw new IllegalArgumentException("Invalid demand " + n);

        boolean resume = false;
        synchronized (this)
        {
            demand = MathUtils.cappedAdd(demand, n);
            if (stalled)
            {
                stalled = false;
                resume = true;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Response demand={}/{}, resume={}", n, demand, resume);
        }

        if (resume)
        {
            if (decoder != null)
                decoder.resume();
            else
                receive();
        }
    }

    protected long demand()
    {
        return demand(LongUnaryOperator.identity());
    }

    private long demand(LongUnaryOperator operator)
    {
        synchronized (this)
        {
            return demand = operator.applyAsLong(demand);
        }
    }

    protected boolean hasDemandOrStall()
    {
        synchronized (this)
        {
            stalled = demand <= 0;
            return !stalled;
        }
    }

    protected HttpExchange getHttpExchange()
    {
        return channel.getHttpExchange();
    }

    protected HttpDestination getHttpDestination()
    {
        return channel.getHttpDestination();
    }

    public boolean isFailed()
    {
        return responseState.get() == ResponseState.FAILURE;
    }

    protected void receive()
    {
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
                LOG.debug("Response {} found protocol handler {}", response, protocolHandler);
        }
        exchange.getConversation().updateResponseListeners(handlerListener);

        if (LOG.isDebugEnabled())
            LOG.debug("Response begin {}", response);
        ResponseNotifier notifier = destination.getResponseNotifier();
        notifier.notifyBegin(conversation.getResponseListeners(), response);

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.BEGIN))
            return true;

        dispose();
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
        if (!updateResponseState(ResponseState.BEGIN, ResponseState.HEADER, ResponseState.TRANSIENT))
            return false;

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
                        URI uri = exchange.getRequest().getURI();
                        if (uri != null)
                            storeCookie(uri, field);
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

        dispose();
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
        if (!updateResponseState(ResponseState.BEGIN, ResponseState.HEADER, ResponseState.TRANSIENT))
            return false;

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response headers {}{}{}", response, System.lineSeparator(), response.getHeaders().toString().trim());
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        List<Response.ResponseListener> responseListeners = exchange.getConversation().getResponseListeners();
        notifier.notifyHeaders(responseListeners, response);
        contentListeners = new ContentListeners(responseListeners);
        contentListeners.notifyBeforeContent(response);

        if (!contentListeners.isEmpty())
        {
            List<String> contentEncodings = response.getHeaders().getCSV(HttpHeader.CONTENT_ENCODING.asString(), false);
            if (contentEncodings != null && !contentEncodings.isEmpty())
            {
                for (ContentDecoder.Factory factory : getHttpDestination().getHttpClient().getContentDecoderFactories())
                {
                    for (String encoding : contentEncodings)
                    {
                        if (factory.getEncoding().equalsIgnoreCase(encoding))
                        {
                            decoder = new Decoder(exchange, factory.newContentDecoder());
                            break;
                        }
                    }
                }
            }
        }

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.HEADERS))
        {
            boolean hasDemand = hasDemandOrStall();
            if (LOG.isDebugEnabled())
                LOG.debug("Response headers hasDemand={} {}", hasDemand, response);
            return hasDemand;
        }

        dispose();
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
    protected boolean responseContent(HttpExchange exchange, ByteBuffer buffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Response content {}{}{}", exchange.getResponse(), System.lineSeparator(), BufferUtil.toDetailString(buffer));
        if (demand() <= 0)
        {
            callback.failed(new IllegalStateException("No demand for response content"));
            return false;
        }
        if (decoder == null)
            return plainResponseContent(exchange, buffer, callback);
        else
            return decodeResponseContent(buffer, callback);
    }

    private boolean plainResponseContent(HttpExchange exchange, ByteBuffer buffer, Callback callback)
    {
        if (!updateResponseState(ResponseState.HEADERS, ResponseState.CONTENT, ResponseState.TRANSIENT))
        {
            callback.failed(new IllegalStateException("Invalid response state " + responseState));
            return false;
        }

        HttpResponse response = exchange.getResponse();
        if (contentListeners.isEmpty())
            callback.succeeded();
        else
            contentListeners.notifyContent(response, buffer, callback);

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.CONTENT))
        {
            boolean hasDemand = hasDemandOrStall();
            if (LOG.isDebugEnabled())
                LOG.debug("Response content {}, hasDemand={}", response, hasDemand);
            return hasDemand;
        }

        dispose();
        terminateResponse(exchange);
        return false;
    }

    private boolean decodeResponseContent(ByteBuffer buffer, Callback callback)
    {
        return decoder.decode(buffer, callback);
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
        terminateResponse(exchange);

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

        if (LOG.isDebugEnabled())
            LOG.debug("Response failure " + exchange.getResponse(), failure);

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
            List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}, notifying {}", failure == null ? "succeeded" : "failed", result, listeners);
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifyComplete(listeners, result);
            if (ordered)
                channel.exchangeTerminated(exchange, result);
        }
    }

    /**
     * Resets the state of this HttpReceiver.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to reset their own state.
     * <p>
     * Either this method or {@link #dispose()} is called.
     */
    protected void reset()
    {
        cleanup();
    }

    /**
     * Disposes the state of this HttpReceiver.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to dispose their own state.
     * <p>
     * Either this method or {@link #reset()} is called.
     */
    protected void dispose()
    {
        assert responseState.get() != ResponseState.TRANSIENT;
        cleanup();
    }

    private void cleanup()
    {
        contentListeners = null;
        if (decoder != null)
            decoder.destroy();
        decoder = null;
        demand = 0;
        stalled = false;
    }

    public boolean abort(HttpExchange exchange, Throwable failure)
    {
        // Update the state to avoid more response processing.
        boolean terminate;
        while (true)
        {
            ResponseState current = responseState.get();
            if (current == ResponseState.FAILURE)
                return false;
            if (updateResponseState(current, ResponseState.FAILURE))
            {
                terminate = current != ResponseState.TRANSIENT;
                break;
            }
        }

        this.failure = failure;

        if (terminate)
            dispose();

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response abort {} {} on {}: {}", response, exchange, getHttpChannel(), failure);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifyFailure(listeners, response, failure);

        // We want to deliver the "complete" event as last,
        // so we emit it here only if no event handlers are
        // executing, otherwise they will emit it.
        if (terminate)
        {
            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
            return true;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Concurrent failure: response termination skipped, performed by helpers");
            return false;
        }
    }

    private boolean updateResponseState(ResponseState from1, ResponseState from2, ResponseState to)
    {
        while (true)
        {
            ResponseState current = responseState.get();
            if (current == from1 || current == from2)
            {
                if (updateResponseState(current, to))
                    return true;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("State update failed: [{},{}] -> {}: {}", from1, from2, to, current);
                return false;
            }
        }
    }

    private boolean updateResponseState(ResponseState from, ResponseState to)
    {
        while (true)
        {
            ResponseState current = responseState.get();
            if (current == from)
            {
                if (responseState.compareAndSet(current, to))
                    return true;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("State update failed: {} -> {}: {}", from, to, current);
                return false;
            }
        }
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

    /**
     * <p>Wraps a list of content listeners, notifies them about content events and
     * tracks individual listener demand to produce a global demand for content.</p>
     */
    private class ContentListeners
    {
        private final Map<Object, Long> demands = new ConcurrentHashMap<>();
        private final LongConsumer demand = HttpReceiver.this::demand;
        private final List<Response.DemandedContentListener> listeners;

        private ContentListeners(List<Response.ResponseListener> responseListeners)
        {
            listeners = new ArrayList<>(responseListeners.size());
            responseListeners.stream()
                .filter(Response.DemandedContentListener.class::isInstance)
                .map(Response.DemandedContentListener.class::cast)
                .forEach(listeners::add);
        }

        private boolean isEmpty()
        {
            return listeners.isEmpty();
        }

        private void notifyBeforeContent(HttpResponse response)
        {
            if (isEmpty())
            {
                // If no listeners, we want to proceed and consume any content.
                demand.accept(1);
            }
            else
            {
                ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
                notifier.notifyBeforeContent(response, this::demand, listeners);
            }
        }

        private void notifyContent(HttpResponse response, ByteBuffer buffer, Callback callback)
        {
            HttpReceiver.this.demand(d -> d - 1);
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifyContent(response, this::demand, buffer, callback, listeners);
        }

        private void demand(Object context, long value)
        {
            if (listeners.size() > 1)
                accept(context, value);
            else
                demand.accept(value);
        }

        private void accept(Object context, long value)
        {
            // Increment the demand for the given listener.
            demands.merge(context, value, MathUtils::cappedAdd);

            // Check if we have demand from all listeners.
            if (demands.size() == listeners.size())
            {
                long minDemand = Long.MAX_VALUE;
                for (Long demand : demands.values())
                {
                    if (demand < minDemand)
                        minDemand = demand;
                }
                if (minDemand > 0)
                {
                    // We are going to demand for minDemand content
                    // chunks, so decrement the listener's demand by
                    // minDemand and remove those that have no demand left.
                    Iterator<Map.Entry<Object, Long>> iterator = demands.entrySet().iterator();
                    while (iterator.hasNext())
                    {
                        Map.Entry<Object, Long> entry = iterator.next();
                        long newValue = entry.getValue() - minDemand;
                        if (newValue == 0)
                            iterator.remove();
                        else
                            entry.setValue(newValue);
                    }

                    // Demand more content chunks for all the listeners.
                    demand.accept(minDemand);
                }
            }
        }
    }

    /**
     * <p>Implements the decoding of content, producing decoded buffers only if there is demand for content.</p>
     */
    private class Decoder implements Destroyable
    {
        private final HttpExchange exchange;
        private final ContentDecoder decoder;
        private ByteBuffer encoded;
        private Callback callback;

        private Decoder(HttpExchange exchange, ContentDecoder decoder)
        {
            this.exchange = exchange;
            this.decoder = Objects.requireNonNull(decoder);
        }

        private boolean decode(ByteBuffer encoded, Callback callback)
        {
            // Store the buffer to decode in case the
            // decoding produces multiple decoded buffers.
            this.encoded = encoded;
            this.callback = callback;

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Response content decoding {} with {}{}{}", response, decoder, System.lineSeparator(), BufferUtil.toDetailString(encoded));

            boolean needInput = decode();
            if (!needInput)
                return false;

            boolean hasDemand = hasDemandOrStall();
            if (LOG.isDebugEnabled())
                LOG.debug("Response content decoded, hasDemand={} {}", hasDemand, response);
            return hasDemand;
        }

        private boolean decode()
        {
            while (true)
            {
                if (!updateResponseState(ResponseState.HEADERS, ResponseState.CONTENT, ResponseState.TRANSIENT))
                {
                    callback.failed(new IllegalStateException("Invalid response state " + responseState));
                    return false;
                }

                DecodeResult result = decodeChunk();

                if (updateResponseState(ResponseState.TRANSIENT, ResponseState.CONTENT))
                {
                    if (result == DecodeResult.NEED_INPUT)
                        return true;
                    if (result == DecodeResult.ABORT)
                        return false;

                    boolean hasDemand = hasDemandOrStall();
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response content decoded chunk, hasDemand={} {}", hasDemand, exchange.getResponse());
                    if (hasDemand)
                        continue;
                    else
                        return false;
                }

                dispose();
                terminateResponse(exchange);
                return false;
            }
        }

        private DecodeResult decodeChunk()
        {
            try
            {
                ByteBuffer buffer;
                while (true)
                {
                    buffer = decoder.decode(encoded);
                    if (buffer.hasRemaining())
                        break;
                    if (!encoded.hasRemaining())
                    {
                        callback.succeeded();
                        encoded = null;
                        callback = null;
                        return DecodeResult.NEED_INPUT;
                    }
                }

                ByteBuffer decoded = buffer;
                HttpResponse response = exchange.getResponse();
                if (LOG.isDebugEnabled())
                    LOG.debug("Response content decoded chunk {}{}{}", response, System.lineSeparator(), BufferUtil.toDetailString(decoded));

                contentListeners.notifyContent(response, decoded, Callback.from(() -> decoder.release(decoded), callback::failed));

                return DecodeResult.DECODE;
            }
            catch (Throwable x)
            {
                callback.failed(x);
                return DecodeResult.ABORT;
            }
        }

        private void resume()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Response content resume decoding {} with {}", exchange.getResponse(), decoder);

            // The content and callback may be null
            // if there is no initial content demand.
            if (callback == null)
            {
                receive();
                return;
            }

            boolean needInput = decode();
            if (needInput)
                receive();
        }

        @Override
        public void destroy()
        {
            if (decoder instanceof Destroyable)
                ((Destroyable)decoder).destroy();
        }
    }

    private enum DecodeResult
    {
        DECODE, NEED_INPUT, ABORT
    }
}
