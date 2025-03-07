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
import java.util.ListIterator;
import java.util.concurrent.Executor;
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
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
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
public abstract class HttpReceiver implements Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpReceiver.class);

    private final HttpChannel channel;
    private final SerializedInvoker invoker;
    private ResponseState responseState = ResponseState.IDLE;
    private ContentSource rawContentSource;
    private Content.Source contentSource;
    private Throwable failure;

    protected HttpReceiver(HttpChannel channel)
    {
        this.channel = channel;
        Executor executor = channel.getHttpDestination().getHttpClient().getExecutor();
        invoker = new SerializedInvoker(HttpReceiver.class.getSimpleName(), executor);
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
            // Probe the protocol handlers.
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
                LOG.debug("Notifying response begin for {} on {}", exchange, this);
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
            LOG.debug("Invoking responseHeader {} for {} on {}", field, exchange, this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseHeader for {} on {}", exchange, this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            responseState = ResponseState.HEADER;
            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying response header {} for {} on {}", field, exchange, this);
            boolean process = exchange.getConversation().getResponseListeners().notifyHeader(response, field);
            if (LOG.isDebugEnabled())
                LOG.debug("Notified response header {}, processing {}", field, (process ? "needed" : "skipped"));
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
            LOG.debug("Invoking responseHeaders for {} on {}", exchange, this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseHeaders for {} on {}", exchange, this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            responseState = ResponseState.HEADERS;
            HttpResponse response = exchange.getResponse();
            HttpFields responseHeaders = response.getHeaders();
            if (LOG.isDebugEnabled())
                LOG.debug("Response headers {}{}{}", response, System.lineSeparator(), responseHeaders.toString().trim());

            // HEAD responses may have Content-Encoding
            // and Content-Length, but have no content.
            // This step may modify the response headers,
            // so must be done before notifying the headers.
            ContentDecoder.Factory decoderFactory = null;
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
                        decoderFactory = factory;
                        beforeDecoding(response, contentEncoding);
                        break;
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Notifying response headers for {} on {}", exchange, this);
            ResponseListeners responseListeners = exchange.getConversation().getResponseListeners();
            responseListeners.notifyHeaders(response);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            if (HttpStatus.isInterim(response.getStatus()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Interim response status {}, succeeding", response.getStatus());
                // The response success event will be serialized and run after this event completes.
                responseSuccess(exchange, this::onInterim);
                return;
            }

            responseState = ResponseState.CONTENT;
            if (rawContentSource != null)
                throw new IllegalStateException();
            rawContentSource = new ContentSource();
            contentSource = rawContentSource;

            if (decoderFactory != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Decoding {} response content for {} on {}", decoderFactory.getEncoding(), exchange, this);
                contentSource = new DecodedContentSource(decoderFactory.newDecoderContentSource(rawContentSource), response);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Notifying response content {} for {} on {}", contentSource, exchange, this);
            responseListeners.notifyContentSource(response, contentSource);
        });
    }

    /**
     * Method to be invoked when response content is available to be read.
     * <p>
     * This method takes care of ensuring the {@link Content.Source} passed to
     * {@link Response.ContentSourceListener#onContentSource(Response, Content.Source)}
     * calls the demand callback.
     */
    protected void responseContentAvailable(HttpExchange exchange)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking responseContentAvailable for {} on {}", exchange, this);

        invoker.run(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseContentAvailable for {} on {}", exchange, this);

            if (exchange.isResponseCompleteOrTerminated())
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("Notifying data available for {} on {}", exchange, this);
            rawContentSource.onDataAvailable();
        });
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
            LOG.debug("Invoking responseSuccess for {} on {}", exchange, this);

        // Mark atomically the response as completed, with respect
        // to concurrency between response success and response failure.
        if (!exchange.responseComplete(null))
            return;

        Runnable successTask = () ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Executing responseSuccess for {} on {}", exchange, this);

            responseState = ResponseState.IDLE;

            reset();

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying response success for {} on {}", exchange, this);
            exchange.getConversation().getResponseListeners().notifySuccess(response);

            // Interim responses do not terminate the exchange.
            if (HttpStatus.isInterim(exchange.getResponse().getStatus()))
                return;

            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
        };

        if (afterSuccessTask == null)
            invoker.run(successTask);
        else
            invoker.run(successTask, afterSuccessTask);
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
        HttpExchange exchange = getHttpExchange();

        if (LOG.isDebugEnabled())
            LOG.debug("Response failure {} for {} on {}", failure, exchange, this);

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

    @Override
    public InvocationType getInvocationType()
    {
        return Invocable.getInvocationType(contentSource);
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
        rawContentSource = null;
        contentSource = null;
    }

    public void abort(HttpExchange exchange, Throwable failure, Promise<Boolean> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Invoking abort for {} on {}", exchange, this, failure);

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
                contentSource.fail(failure);

            dispose();

            HttpResponse response = exchange.getResponse();
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying response failure {} for {} on {}", failure, exchange, this);
            exchange.getConversation().getResponseListeners().notifyFailure(response, failure);

            // Mark atomically the response as terminated, with
            // respect to concurrency between request and response.
            terminateResponse(exchange);
            promise.succeeded(true);
        });
    }

    private void beforeDecoding(Response response, String contentEncoding)
    {
        HttpResponse httpResponse = (HttpResponse)response;
        httpResponse.headers(headers ->
        {
            boolean seenContentEncoding = false;
            for (ListIterator<HttpField> iterator = headers.listIterator(headers.size()); iterator.hasPrevious();)
            {
                HttpField field = iterator.previous();
                HttpHeader header = field.getHeader();
                if (header == HttpHeader.CONTENT_LENGTH)
                {
                    // Content-Length is not valid anymore while we are decoding.
                    iterator.remove();
                }
                else if (header == HttpHeader.CONTENT_ENCODING && !seenContentEncoding)
                {
                    // Last Content-Encoding should be removed/modified as the content will be decoded.
                    seenContentEncoding = true;
                    // TODO: introduce HttpFields.removeLast() or similar.
                    //  Use the contentEncoding parameter.
                    String value = field.getValue();
                    int comma = value.lastIndexOf(",");
                    if (comma < 0)
                        iterator.remove();
                    else
                        iterator.set(new HttpField(HttpHeader.CONTENT_ENCODING, value.substring(0, comma)));
                }
            }
        });
    }

    private void afterDecoding(Response response, long decodedLength)
    {
        HttpResponse httpResponse = (HttpResponse)response;
        httpResponse.headers(headers ->
        {
            headers.remove(HttpHeader.TRANSFER_ENCODING);
            headers.put(HttpHeader.CONTENT_LENGTH, decodedLength);
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

    private class DecodedContentSource implements Content.Source, Invocable
    {
        private static final Logger LOG = LoggerFactory.getLogger(DecodedContentSource.class);

        private final Content.Source source;
        private final Response response;
        private long decodedLength;
        private boolean last;

        private DecodedContentSource(Content.Source source, Response response)
        {
            this.source = source;
            this.response = response;
        }

        @Override
        public long getLength()
        {
            return source.getLength();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return Invocable.getInvocationType(source);
        }

        @Override
        public Content.Chunk read()
        {
            while (true)
            {
                Content.Chunk chunk = source.read();

                if (LOG.isDebugEnabled())
                    LOG.debug("Decoded chunk {}", chunk);

                if (chunk == null)
                    return null;

                if (chunk.isEmpty() && !chunk.isLast())
                {
                    chunk.release();
                    continue;
                }

                decodedLength += chunk.remaining();

                if (chunk.isLast() && !last)
                {
                    last = true;
                    afterDecoding(response, decodedLength);
                }

                return chunk;
            }
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            Runnable demand = new Invocable.ReadyTask(Invocable.getInvocationType(demandCallback), () -> invoker.run(demandCallback));
            source.demand(demand);
        }

        @Override
        public void fail(Throwable failure)
        {
            source.fail(failure);
        }

        @Override
        public void fail(Throwable failure, boolean last)
        {
            source.fail(failure, last);
        }

        @Override
        public boolean rewind()
        {
            boolean rewound = source.rewind();
            if (rewound)
            {
                decodedLength = 0;
                last = false;
            }
            return rewound;
        }
    }

    private class ContentSource implements Content.Source, Invocable
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

            if (LOG.isDebugEnabled())
                LOG.debug("Read {} from {}", current, this);

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

        private void onDataAvailable()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onDataAvailable on {}", this);
            invoker.assertCurrentThreadInvoking();
            // The onDataAvailable() method is only ever called
            // by the invoker so avoid using the invoker again.
            invokeDemandCallback(false);
        }

        @Override
        public InvocationType getInvocationType()
        {
            Runnable demandCallback = demandCallbackRef.get();
            if (demandCallback != null)
                return Invocable.getInvocationType(demandCallback);
            return Invocable.getInvocationType(getHttpChannel().getConnection());
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
            invoker.run(processDemand);
        }

        private void processDemand()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Processing demand on {}", this);

            invoker.assertCurrentThreadInvoking();

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

            // The processDemand() method is only ever called
            // by the invoker so avoid using the invoker again.
            invokeDemandCallback(false);
        }

        private void invokeDemandCallback(boolean invoke)
        {
            Runnable demandCallback = demandCallbackRef.getAndSet(null);
            if (LOG.isDebugEnabled())
                LOG.debug("Invoking demand callback {} on {}", demandCallback, this);
            if (demandCallback == null)
                return;
            try
            {
                if (invoke)
                {
                    invoker.run(demandCallback);
                }
                else
                {
                    invoker.assertCurrentThreadInvoking();
                    demandCallback.run();
                }
            }
            catch (Throwable x)
            {
                fail(x);
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

        private boolean error(Throwable failure)
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
