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
import java.util.function.Consumer;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.SerializedInvoker;
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
 * <li>{@link #responseSuccess(HttpExchange)}, when the response is successful</li>
 * </ol>
 * At any time, subclasses may invoke {@link #responseFailure(Throwable, Consumer)} to indicate that the response has failed
 * (for example, because of I/O exceptions).
 * At any time, user threads may abort the response which will cause {@link #responseFailure(Throwable, Consumer)} to be
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

    private final SerializedInvoker invoker = new SerializedInvoker();
    private final ContentListeners contentListeners = new ContentListeners();
    private final HttpChannel channel;
    private ResponseState responseState = ResponseState.IDLE;
    private Content.Source contentSource;
    private Throwable failure;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected abstract Content.Source newContentSource();

    private Content.Source createContentSource()
    {
        if (contentSource != null)
            throw new IllegalStateException();
        return new SerializedContentSource(newContentSource());
    }

    // TODO: move Wrapper to Content.Source.Wrapper.
    private class SerializedContentSource implements Content.Source
    {
        private final Content.Source delegate;

        private SerializedContentSource(Content.Source delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public long getLength()
        {
            return delegate.getLength();
        }

        @Override
        public Content.Chunk read()
        {
            return delegate.read();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            delegate.demand(() -> invoker.run(demandCallback));
        }

        @Override
        public void fail(Throwable failure)
        {
            delegate.fail(failure);
        }

        @Override
        public boolean rewind()
        {
            return delegate.rewind();
        }
    }

    private static class DecodingContentSource extends ContentSourceTransformer
    {
        private static final Logger LOG = LoggerFactory.getLogger(DecodingContentSource.class);

        private final ContentDecoder _decoder;
        private volatile Content.Chunk _chunk;

        public DecodingContentSource(Content.Source rawSource, ContentDecoder decoder)
        {
            super(rawSource);
            _decoder = decoder;
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
        return responseState == ResponseState.FAILURE;
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
     */
    protected void responseBegin(HttpExchange exchange)
    {
        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("responseBegin");

            if (exchange.isResponseComplete())
                return;
            responseState = ResponseState.BEGIN;
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
                if (LOG.isDebugEnabled()) LOG.debug("Response {} found protocol handler {}", response, protocolHandler);
            }
            exchange.getConversation().updateResponseListeners(handlerListener);

            if (LOG.isDebugEnabled()) LOG.debug("Response begin {}", response);
            ResponseNotifier notifier = destination.getResponseNotifier();
            notifier.notifyBegin(conversation.getResponseListeners(), response);
        });
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
     * @param field    the response HTTP field
     */
    protected void responseHeader(HttpExchange exchange, HttpField field)
    {
        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("responseHeader");

            if (exchange.isResponseComplete())
                return;
            responseState = ResponseState.HEADER;
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
        });
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
            if (LOG.isDebugEnabled()) LOG.debug("Unable to store cookies {} from {}", field, uri, x);
        }
    }

    /**
     * Method to be invoked after all response HTTP headers are available.
     * <p>
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.HeadersListener}s.
     *
     * @param exchange the HTTP exchange
     */
    protected void responseHeaders(HttpExchange exchange)
    {
        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("responseHeaders");

            if (exchange.isResponseComplete())
                return;
            responseState = ResponseState.HEADERS;
            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Response headers {}{}{}", response, System.lineSeparator(), response.getHeaders().toString().trim());
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            List<Response.ResponseListener> responseListeners = exchange.getConversation().getResponseListeners();
            notifier.notifyHeaders(responseListeners, response);

            if (HttpStatus.isInterim(response.getStatus()))
            {
                responseSuccess(exchange);
                return;
            }

            responseState = ResponseState.CONTENT;

            contentSource = createContentSource();

            List<Response.ContentSourceListener> contentListeners = responseListeners.stream()
                    .filter(l -> l instanceof Response.ContentSourceListener)
                    .map(Response.ContentSourceListener.class::cast)
                    .toList();

            if (!contentListeners.isEmpty())
            {
                List<String> contentEncodings = response.getHeaders().getCSV(HttpHeader.CONTENT_ENCODING.asString(), false);
                if (contentEncodings != null && !contentEncodings.isEmpty())
                {
                    both:
                    for (ContentDecoder.Factory factory : getHttpDestination().getHttpClient().getContentDecoderFactories())
                    {
                        for (String encoding : contentEncodings)
                        {
                            if (factory.getEncoding().equalsIgnoreCase(encoding))
                            {
                                contentSource = new DecodingContentSource(contentSource, factory.newContentDecoder());
                                break both;
                            }
                        }
                    }
                }
            }

            notifier.notifyContent(response, contentSource, contentListeners);
        });
    }

    /**
     * Method to be invoked when the response is successful.
     * <p>
     * This method takes case of notifying {@link org.eclipse.jetty.client.api.Response.SuccessListener}s and possibly
     * {@link org.eclipse.jetty.client.api.Response.CompleteListener}s (if the exchange is completed).
     *
     * @param exchange the HTTP exchange
     */
    protected void responseSuccess(HttpExchange exchange)
    {
        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("responseSuccess");

            // Mark atomically the response as completed, with respect
            // to concurrency between response success and response failure.
            if (!exchange.responseComplete(null))
                return;

            responseState = ResponseState.IDLE;

            reset();

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled()) LOG.debug("Response success {}", response);
            List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifySuccess(listeners, response);

            // Interim responses do not terminate the exchange.
            if (HttpStatus.isInterim(exchange.getResponse().getStatus())) return;

            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
        });
    }

    /**
     * Method to be invoked when the response is failed.
     * <p>
     * This method takes care of notifying {@link org.eclipse.jetty.client.api.Response.FailureListener}s.
     *
     * @param failure the response failure
     */
    protected void responseFailure(Throwable failure)
    {
        responseFailure(failure, (failed) ->
        {
        });
    }

    protected void responseFailure(Throwable failure, Consumer<Boolean> cb)
    {
        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("responseFailure");

            HttpExchange exchange = getHttpExchange();
            // In case of a response error, the failure has already been notified
            // and it is possible that a further attempt to read in the receive
            // loop throws an exception that reenters here but without exchange;
            // or, the server could just have timed out the connection.
            if (exchange == null)
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("Response failure {}", exchange.getResponse(), failure);

            // Mark atomically the response as completed, with respect
            // to concurrency between response success and response failure.
            if (exchange.responseComplete(failure))
                abort(exchange, failure, cb);
        });
    }

    private void terminateResponse(HttpExchange exchange)
    {
        Result result = exchange.terminateResponse();
        terminateResponse(exchange, result);
    }

    private void terminateResponse(HttpExchange exchange, Result result)
    {
        HttpResponse response = exchange.getResponse();

        if (LOG.isDebugEnabled()) LOG.debug("Response complete {}, result: {}", response, result);

        if (result != null)
        {
            result = channel.exchangeTerminating(exchange, result);
            boolean ordered = getHttpDestination().getHttpClient().isStrictEventOrdering();
            if (!ordered) channel.exchangeTerminated(exchange, result);
            List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}, notifying {}", failure == null ? "succeeded" : "failed", result, listeners);
            ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
            notifier.notifyComplete(listeners, result);
            if (ordered) channel.exchangeTerminated(exchange, result);
        }
    }

    /**
     * Resets the state of this HttpReceiver.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to reset their own state.
     * <p>
     * Either this method or {@link #dispose(Throwable)} is called.
     */
    protected void reset()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("resetting {}", this);
        cleanup();
    }

    /**
     * Disposes the state of this HttpReceiver.
     * <p>
     * Subclasses should override (but remember to call {@code super}) to dispose their own state.
     * <p>
     * Either this method or {@link #reset()} is called.
     */
    protected void dispose(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disposing {}", this);
        cleanup(x);
    }

    private void cleanup()
    {
        cleanup(null);
    }

    private void cleanup(Throwable x)
    {
        contentListeners.clear();
        if (x != null && contentSource != null)
            contentSource.fail(x);
        contentSource = null;
        failure = null;
        responseState = ResponseState.IDLE;
    }

    public void abort(HttpExchange exchange, Throwable failure)
    {
        abort(exchange, failure, (f) ->
        {
        });
    }

    public void abort(HttpExchange exchange, Throwable failure, Consumer<Boolean> cb)
    {
        invoker.run(() ->
        {
            boolean failed = false;
            try
            {
                if (responseState == ResponseState.IDLE || responseState == ResponseState.FAILURE)
                    return;

                responseState = ResponseState.FAILURE;
                failed = true;

                this.failure = failure;

                dispose(failure);

                HttpResponse response = exchange.getResponse();
                if (LOG.isDebugEnabled())
                    LOG.debug("Response abort {} {} on {}: {}", response, exchange, getHttpChannel(), failure);
                List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
                ResponseNotifier notifier = getHttpDestination().getResponseNotifier();
                notifier.notifyFailure(listeners, response, failure);

                // Mark atomically the response as terminated, with
                // respect to concurrency between request and response.
                terminateResponse(exchange);
            }
            finally
            {
                cb.accept(failed);
            }
        });
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

    }
}
