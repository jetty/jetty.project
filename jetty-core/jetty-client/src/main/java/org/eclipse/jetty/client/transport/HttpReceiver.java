//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.transport;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.AutoLock;
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
 * <li>{@link #responseSuccess(HttpExchange, Runnable)}, when the response is successful</li>
 * </ol>
 * At any time, subclasses may invoke {@link #responseFailure(Throwable, Promise)} to indicate that the response has failed
 * (for example, because of I/O exceptions).
 * At any time, user threads may abort the response which will cause {@link #responseFailure(Throwable, Promise)} to be
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
    private final HttpChannel channel;
    private ResponseState responseState = ResponseState.IDLE;
    private NotifiableContentSource contentSource;
    private Throwable failure;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
    }

    /**
     * Reads a chunk of data.
     * <p>
     * If no data was read, {@code null} is returned and if {@code fillInterestIfNeeded}
     * is {@code true} then fill interest is registered.
     * <p>
     * The returned chunk of data may be the last one or an error exactly like
     * {@link Content.Source#read()} specifies.
     *
     * @param fillInterestIfNeeded true to register for fill interest when no data was read.
     * @return the chunk of data that was read, or {@code null} if nothing was read.
     */
    protected abstract Content.Chunk read(boolean fillInterestIfNeeded);

    /**
     * Prepare for the next step after an interim response was read.
     */
    protected abstract void onInterim();

    /**
     * Fails the receiver and closes the underlying stream.
     * @param failure the failure.
     */
    protected abstract void failAndClose(Throwable failure);

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

    protected boolean hasContent()
    {
        return contentSource != null;
    }

    /**
     * Method to be invoked when the response status code is available.
     * <p>
     * Subclasses must have set the response status code on the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p>
     * This method takes case of notifying {@link Response.BeginListener}s.
     *
     * @param exchange the HTTP exchange
     */
    protected void responseBegin(HttpExchange exchange)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking responseBegin for {} on {}", exchange, this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseBegin for {} on {}", exchange, this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            responseState = ResponseState.BEGIN;
            HttpResponse response = exchange.getResponse();
            HttpConversation conversation = exchange.getConversation();
            // Probe the protocol handlers
            HttpClient client = getHttpDestination().getHttpClient();
            ProtocolHandler protocolHandler = client.findProtocolHandler(exchange.getRequest(), response);
            Response.Listener handlerListener = null;
            if (protocolHandler != null)
            {
                handlerListener = protocolHandler.getResponseListener();
                if (LOG.isDebugEnabled())
                    LOG.debug("Response {} found protocol handler {}", response, protocolHandler);
            }
            conversation.updateResponseListeners(handlerListener);

            if (LOG.isDebugEnabled())
                LOG.debug("Response begin {}", response);
            conversation.getResponseListeners().notifyBegin(response);
        });
    }

    /**
     * Method to be invoked when a response HTTP header is available.
     * <p>
     * Subclasses must not have added the header to the {@link Response} object of the {@link HttpExchange}
     * prior invoking this method.
     * <p>
     * This method takes case of notifying {@link Response.HeaderListener}s and storing cookies.
     *
     * @param exchange the HTTP exchange
     * @param field    the response HTTP field
     */
    protected void responseHeader(HttpExchange exchange, HttpField field)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking responseHeader for {} on {}", field, this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseHeader on {}", this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            responseState = ResponseState.HEADER;
            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying header {}", field);
            boolean process = exchange.getConversation().getResponseListeners().notifyHeader(response, field);
            if (LOG.isDebugEnabled())
                LOG.debug("Header {} notified, {}processing needed", field, (process ? "" : "no "));
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
        getHttpDestination().getHttpClient().putCookie(uri, field);
    }

    /**
     * Method to be invoked after all response HTTP headers are available.
     * <p>
     * This method takes care of notifying {@link Response.HeadersListener}s.
     *
     * @param exchange the HTTP exchange
     */
    protected void responseHeaders(HttpExchange exchange)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking responseHeaders on {}", this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseHeaders on {}", this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            responseState = ResponseState.HEADERS;
            HttpResponse response = exchange.getResponse();
            HttpFields responseHeaders = response.getHeaders();
            if (LOG.isDebugEnabled())
                LOG.debug("Response headers {}{}{}", response, System.lineSeparator(), responseHeaders.toString().trim());

            // HEAD responses may have Content-Encoding
            // and Content-Length, but have no content.
            ContentDecoder decoder = null;
            if (!HttpMethod.HEAD.is(exchange.getRequest().getMethod()))
            {
                // Content-Encoding may have multiple values in the order they
                // are applied, but we only support one decoding pass, the last one.
                String contentEncoding = responseHeaders.getLast(HttpHeader.CONTENT_ENCODING);
                if (contentEncoding != null)
                {
                    int comma = contentEncoding.indexOf(",");
                    if (comma > 0)
                    {
                        List<String> values = new QuotedCSV(false, contentEncoding).getValues();
                        contentEncoding = values.get(values.size() - 1);
                    }
                }
                // If there is a matching content decoder factory, build a decoder.
                for (ContentDecoder.Factory factory : getHttpDestination().getHttpClient().getContentDecoderFactories())
                {
                    if (factory.getEncoding().equalsIgnoreCase(contentEncoding))
                    {
                        decoder = factory.newContentDecoder();
                        decoder.beforeDecoding(response);
                        break;
                    }
                }
            }

            ResponseListeners responseListeners = exchange.getConversation().getResponseListeners();
            responseListeners.notifyHeaders(response);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            if (HttpStatus.isInterim(response.getStatus()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Interim response status {}, succeeding", response.getStatus());
                responseSuccess(exchange, this::onInterim);
                return;
            }

            responseState = ResponseState.CONTENT;
            if (contentSource != null)
                throw new IllegalStateException();
            contentSource = new ContentSource();

            if (decoder != null)
                contentSource = new DecodingContentSource(contentSource, invoker, decoder, response);

            if (LOG.isDebugEnabled())
                LOG.debug("Response content {} {}", response, contentSource);
            responseListeners.notifyContentSource(response, contentSource);
        });
    }

    /**
     * Method to be invoked when response content is available to be read.
     * <p>
     * This method takes care of ensuring the {@link Content.Source} passed to
     * {@link Response.ContentSourceListener#onContentSource(Response, Content.Source)} calls the
     * demand callback.
     */
    protected void responseContentAvailable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Response content available on {}", this);
        contentSource.onDataAvailable();
    }

    /**
     * Method to be invoked when the response is successful.
     * <p>
     * This method takes care of notifying {@link Response.SuccessListener}s and possibly
     * {@link Response.CompleteListener}s (if the exchange is completed).
     *
     * @param exchange the HTTP exchange
     * @param afterSuccessTask an optional task to invoke afterwards
     */
    protected void responseSuccess(HttpExchange exchange, Runnable afterSuccessTask)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking responseSuccess on {}", this);

        // Mark atomically the response as completed, with respect
        // to concurrency between response success and response failure.
        if (!exchange.responseComplete(null))
            return;

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseSuccess on {}", this);

            responseState = ResponseState.IDLE;

            reset();

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Response success {}", response);
            exchange.getConversation().getResponseListeners().notifySuccess(response);

            // Interim responses do not terminate the exchange.
            if (HttpStatus.isInterim(exchange.getResponse().getStatus()))
                return;

            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
        }, afterSuccessTask);
    }

    /**
     * Method to be invoked when the response is failed.
     * <p>
     * This method takes care of notifying {@link Response.FailureListener}s.
     *
     * @param failure the response failure
     */
    protected void responseFailure(Throwable failure, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Failing with {} on {}", failure, this);

        HttpExchange exchange = getHttpExchange();

        // Mark atomically the response as completed, with respect
        // to concurrency between response success and response failure.
        if (exchange != null && exchange.responseComplete(failure))
        {
            abort(exchange, failure, promise);
        }
        else
        {
            // The response was already completed (either successfully
            // or with a failure) by a previous event, bail out.
            promise.succeeded(false);
        }
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
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}", failure == null ? "succeeded" : "failed", result);
            exchange.getConversation().getResponseListeners().notifyComplete(result);
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
        if (LOG.isDebugEnabled())
            LOG.debug("Resetting {}", this);
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
        if (LOG.isDebugEnabled())
            LOG.debug("Disposing {}", this);
        cleanup();
    }

    private void cleanup()
    {
        if (contentSource != null)
            contentSource.destroy();
        contentSource = null;
    }

    public void abort(HttpExchange exchange, Throwable failure, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking abort with {} on {}", failure, this);

        if (!exchange.isResponseCompleteOrTerminated())
            throw new IllegalStateException();

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing abort with {} on {}", failure, this);

            if (responseState == ResponseState.FAILURE)
            {
                promise.succeeded(false);
                return;
            }

            responseState = ResponseState.FAILURE;
            this.failure = failure;
            if (contentSource != null)
                contentSource.error(failure);
            dispose();

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Response abort {} {} on {}", response, exchange, getHttpChannel(), failure);
            exchange.getConversation().getResponseListeners().notifyFailure(response, failure);

            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
            promise.succeeded(true);
        });
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(ex=%s,rsp=%s,failure=%s)",
                getClass().getSimpleName(),
                hashCode(),
                getHttpExchange(),
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

    private interface NotifiableContentSource extends Content.Source, Destroyable
    {
        boolean error(Throwable failure);

        void onDataAvailable();

        @Override
        default void destroy()
        {
        }
    }

    private static class DecodingContentSource extends ContentSourceTransformer implements NotifiableContentSource
    {
        private static final Logger LOG = LoggerFactory.getLogger(DecodingContentSource.class);

        private final ContentDecoder _decoder;
        private final Response _response;
        private volatile Content.Chunk _chunk;

        private DecodingContentSource(NotifiableContentSource rawSource, SerializedInvoker invoker, ContentDecoder decoder, Response response)
        {
            super(rawSource, invoker);
            _decoder = decoder;
            _response = response;
        }

        @Override
        protected NotifiableContentSource getContentSource()
        {
            return (NotifiableContentSource)super.getContentSource();
        }

        @Override
        public void onDataAvailable()
        {
            getContentSource().onDataAvailable();
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
                if (Content.Chunk.isFailure(_chunk))
                {
                    Content.Chunk failure = _chunk;
                    _chunk = Content.Chunk.next(failure);
                    return failure;
                }

                // Retain the input chunk because its ByteBuffer will be referenced by the Inflater.
                if (retain)
                    _chunk.retain();
                if (LOG.isDebugEnabled())
                    LOG.debug("decoding: {}", _chunk);
                RetainableByteBuffer decodedBuffer = _decoder.decode(_chunk.getByteBuffer());
                if (LOG.isDebugEnabled())
                    LOG.debug("decoded: {}", decodedBuffer);

                if (decodedBuffer != null && decodedBuffer.hasRemaining())
                {
                    // The decoded ByteBuffer is a transformed "copy" of the
                    // compressed one, so it has its own reference counter.
                    if (decodedBuffer.canRetain())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("returning decoded content");
                        return Content.Chunk.asChunk(decodedBuffer.getByteBuffer(), false, decodedBuffer);
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("returning non-retainable decoded content");
                        return Content.Chunk.from(decodedBuffer.getByteBuffer(), false);
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decoding produced no content");
                    if (decodedBuffer != null)
                        decodedBuffer.release();

                    if (!_chunk.hasRemaining())
                    {
                        Content.Chunk result = _chunk.isLast() ? Content.Chunk.EOF : null;
                        if (LOG.isDebugEnabled())
                            LOG.debug("Could not decode more from this chunk, releasing it, r={}", result);
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

        @Override
        public boolean error(Throwable failure)
        {
            if (_chunk != null)
                _chunk.release();
            _chunk = null;
            return getContentSource().error(failure);
        }

        @Override
        public void destroy()
        {
            _decoder.afterDecoding(_response);
            getContentSource().destroy();
        }
    }

    /**
     * This Content.Source implementation guarantees that all {@link #read(boolean)} calls
     * happening from a {@link #demand(Runnable)} callback must be serialized.
     */
    private class ContentSource implements NotifiableContentSource
    {
        private static final Logger LOG = LoggerFactory.getLogger(ContentSource.class);

        private final AtomicReference<Runnable> demandCallbackRef = new AtomicReference<>();
        private final AutoLock lock = new AutoLock();
        private final Runnable processDemand = this::processDemand;
        private Content.Chunk currentChunk;

        @Override
        public Content.Chunk read()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Reading from {}", this);

            Content.Chunk current;
            try (AutoLock ignored = lock.lock())
            {
                current = currentChunk;
                currentChunk = Content.Chunk.next(current);
                if (current != null)
                    return current;
            }

            current = HttpReceiver.this.read(false);

            try (AutoLock ignored = lock.lock())
            {
                if (currentChunk != null)
                {
                    // There was a concurrent call to fail().
                    if (current != null)
                        current.release();
                    return currentChunk;
                }
                currentChunk = Content.Chunk.next(current);
                return current;
            }
        }

        @Override
        public void onDataAvailable()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onDataAvailable on {}", this);
            // The demandCallback will call read() that will itself call
            // HttpReceiver.read(boolean) so it must be called by the invoker.
            invokeDemandCallback(true);
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Registering demand on {}", this);
            if (demandCallback == null)
                throw new IllegalArgumentException();
            if (!demandCallbackRef.compareAndSet(null, demandCallback))
                throw new IllegalStateException();
            // The processDemand method may call HttpReceiver.read(boolean)
            // so it must be called by the invoker.
            invoker.run(processDemand);
        }

        private void processDemand()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Processing demand on {}", this);

            Content.Chunk current;
            try (AutoLock ignored = lock.lock())
            {
                current = currentChunk;
            }

            if (current == null)
            {
                current = HttpReceiver.this.read(true);
                if (current == null)
                    return;

                try (AutoLock ignored = lock.lock())
                {
                    if (currentChunk != null)
                    {
                        // There was a concurrent call to fail().
                        current.release();
                        return;
                    }
                    currentChunk = current;
                }
            }

            // The processDemand method is only ever called by the
            // invoker so there is no need to use the latter here.
            invokeDemandCallback(false);
        }

        private void invokeDemandCallback(boolean invoke)
        {
            Runnable demandCallback = demandCallbackRef.getAndSet(null);
            if (LOG.isDebugEnabled())
                LOG.debug("Invoking demand callback on {}", this);
            if (demandCallback != null)
            {
                try
                {
                    if (invoke)
                        invoker.run(demandCallback);
                    else
                        demandCallback.run();
                }
                catch (Throwable x)
                {
                    fail(x);
                }
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failing {}", this);
            boolean failed = error(failure);
            if (failed)
                HttpReceiver.this.failAndClose(failure);
            invokeDemandCallback(true);
        }

        @Override
        public boolean error(Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Erroring {}", this);
            try (AutoLock ignored = lock.lock())
            {
                if (Content.Chunk.isFailure(currentChunk))
                {
                    Throwable cause = currentChunk.getFailure();
                    if (!currentChunk.isLast())
                        currentChunk = Content.Chunk.from(cause, true);
                    ExceptionUtil.addSuppressedIfNotAssociated(cause, failure);
                    return false;
                }
                if (currentChunk != null)
                    currentChunk.release();
                currentChunk = Content.Chunk.from(failure);
            }
            return true;
        }

        private Content.Chunk chunk()
        {
            try (AutoLock ignored = lock.lock())
            {
                return currentChunk;
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{c=%s,d=%s}", getClass().getSimpleName(), hashCode(), chunk(), demandCallbackRef);
        }
    }
}
