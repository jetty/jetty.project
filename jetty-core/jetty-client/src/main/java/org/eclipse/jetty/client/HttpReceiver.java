//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <li>{@link #responseContent(HttpExchange, Callback)}, when HTTP content is available</li>
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
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiver.class);

    private final AtomicReference<ResponseState> responseState = new AtomicReference<>(ResponseState.IDLE);
    private final ContentListeners contentListeners = new ContentListeners();
    private ReceiverContentSource contentSource;
    private final HttpChannel channel;
    private volatile boolean firstContent = true;
    private Throwable failure;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
        this.contentSource = newContentSource();
    }

    protected abstract ReceiverContentSource newContentSource();

    // TODO get rid of this interface so that DecodingContentSource can be client/server agnostic
    protected interface ReceiverContentSource extends Content.Source
    {
        void onDataAvailable(Callback callback);

        void close();

        boolean isClosed();
    }

    private static class DecodingContentSource extends ContentSourceTransformer implements ReceiverContentSource
    {
        private static final Logger LOG = LoggerFactory.getLogger(DecodingContentSource.class);

        private final ReceiverContentSource _rawSource;
        private final ContentDecoder _decoder;
        private volatile Content.Chunk _chunk;

        public DecodingContentSource(ReceiverContentSource rawSource, ContentDecoder decoder)
        {
            super(rawSource);
            _rawSource = rawSource;
            _decoder = decoder;
        }

        @Override
        public void onDataAvailable(Callback callback)
        {
            _rawSource.onDataAvailable(callback);
        }

        @Override
        public void close()
        {
            _rawSource.close();
            if (_chunk != null)
            {
                _chunk.release();
                _chunk = null;
            }
        }

        @Override
        public boolean isClosed()
        {
            return _rawSource.isClosed();
        }

        @Override
        protected Content.Chunk transform(Content.Chunk inputChunk)
        {
            while (true)
            {
                boolean retain = _chunk == null;
                if (LOG.isDebugEnabled())
                    LOG.debug("input: {}, chunk: {}, retain? {}", inputChunk, _chunk, retain);
                if (_chunk == null)
                    _chunk = inputChunk;
                if (_chunk == null)
                    return null;
                if (_chunk instanceof Content.Chunk.Error)
                    return _chunk;
                // TODO we are returning EOF too early, potentially missing one decode step. Write test & rework.
//            if (_chunk.isLast() && !_chunk.hasRemaining())
//                return Content.Chunk.EOF;

                // Retain the input chunk because its ByteBuffer will be referenced by the Inflater.
                if (retain && _chunk.hasRemaining())
                    _chunk.retain();
                if (LOG.isDebugEnabled())
                    LOG.debug("decoding: {}", _chunk);
                ByteBuffer decodedBuffer = _decoder.decode(_chunk.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("decoded: {}", BufferUtil.toDetailString(decodedBuffer));

                if (BufferUtil.hasContent(decodedBuffer))
                {
                    // The decoded ByteBuffer is a transformed "copy" of the
                    // compressed one, so it has its own reference counter.
                    // TODO last should always be false here
    //                return Content.Chunk.from(decodedBuffer, _chunk.isLast() && !_chunk.hasRemaining(), _decoder::release);
                    if (LOG.isDebugEnabled())
                        LOG.debug("returning decoded content");
                    return Content.Chunk.from(decodedBuffer, false, _decoder::release);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decoding produced no content");

                    if (!_chunk.hasRemaining())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Could not decode more from this chunk, releasing it");
                        Content.Chunk result = _chunk.isLast() ? Content.Chunk.EOF : null;
                        _chunk.release();
                        _chunk = null;
                        return result;
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("retrying transformation");
                    }
                }
            }
        }
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
            response.addHeader(field);
            HttpHeader fieldHeader = field.getHeader();
            if (fieldHeader != null)
            {
                switch (fieldHeader)
                {
                    case SET_COOKIE, SET_COOKIE2 ->
                    {
                        URI uri = exchange.getRequest().getURI();
                        if (uri != null)
                            storeCookie(uri, field);
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
                LOG.debug("Unable to store cookies {} from {}", field, uri, x);
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
        contentListeners.reset(responseListeners);

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
                            contentSource = new DecodingContentSource(contentSource, factory.newContentDecoder());
                            break;
                        }
                    }
                }
            }
        }

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.HEADERS))
        {
            // Tell the parser to always advance either to the content or to the end of the response.
            return true;
        }

        dispose();
        terminateResponse(exchange);
        return false;
    }

    // TODO change signature to boolean responseContent(HttpExchange exchange, Content.Source source)
    protected Runnable firstResponseContent(HttpExchange exchange, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("firstResponseContent");
        contentSource.onDataAvailable(callback);
        firstContent = false;
        return () ->
        {
            contentListeners.notifyContent(exchange.getResponse());
            if (contentSource.isClosed())
                reset();
        };
    }

    protected void notifyDataAvailable()
    {
        contentSource.onDataAvailable(Callback.NOOP);
    }

    /**
     * Method to be invoked when response HTTP content is available.
     * <p>
     * This method takes case of decoding the content, if necessary, and notifying {@link org.eclipse.jetty.client.api.Response.ContentListener}s.
     *
     * @param exchange the HTTP exchange
     * @param callback the callback
     * @return whether the processing should continue
     */
    // TODO this method should go away, make sure state changes aren't needed anymore (they should not be, they're about checking there was no concurrent failure triggered by the app)
    protected boolean responseContent(HttpExchange exchange, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Response content {}", exchange.getResponse());

        if (!updateResponseState(ResponseState.HEADERS, ResponseState.CONTENT, ResponseState.TRANSIENT))
        {
            callback.failed(new IllegalStateException("Invalid response state " + responseState));
            return false;
        }

        contentSource.onDataAvailable(callback); // make sure state is TRANSIENT while the app code is running

        if (updateResponseState(ResponseState.TRANSIENT, ResponseState.CONTENT))
        {
            // Stop the parser immediately otherwise the parser may parse EOF and emit
            // responseSuccess before the previously enqueued content gets read.
            return false;
        }

        dispose();
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
        if (LOG.isDebugEnabled())
            LOG.debug("responseSuccess closing contentSource");
        contentSource.close();

        // Reset to be ready for another response.
        if (firstContent)
            reset();

        HttpResponse response = exchange.getResponse();
        if (LOG.isDebugEnabled())
            LOG.debug("Response success {}", response);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
        notifier.notifySuccess(listeners, response);

        // Interim responses do not terminate the exchange.
        if (HttpStatus.isInterim(exchange.getResponse().getStatus()))
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
            LOG.debug("Response failure {}", exchange.getResponse(), failure);

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
            LOG.debug("Response complete {}, result: {}", response, result);

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
    // TODO reconcile dispose(), dispose(Throwable), cleanup() and cleanup(Throwable)
    protected void dispose()
    {
        dispose(null);
    }

    protected void dispose(Throwable x)
    {
        assert responseState.get() != ResponseState.TRANSIENT;
        cleanup(x);
    }

    private void cleanup()
    {
        cleanup(null);
    }

    private void cleanup(Throwable x)
    {
        contentListeners.clear();
        if (x != null)
            contentSource.fail(x);
        contentSource = newContentSource();
        firstContent = true;
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
            dispose(failure);

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
        private final List<Response.ContentSourceListener> listeners = new ArrayList<>(1);

        private void clear()
        {
            listeners.clear();
        }

        private void reset(List<Response.ResponseListener> responseListeners)
        {
            clear();
            for (Response.ResponseListener listener : responseListeners)
            {
                if (listener instanceof Response.ContentSourceListener)
                    listeners.add((Response.ContentSourceListener)listener);
            }
        }

        private boolean isEmpty()
        {
            return listeners.isEmpty();
        }

        private void notifyContent(HttpResponse response)
        {
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifyContent(response, contentSource, listeners);
        }
    }
}
