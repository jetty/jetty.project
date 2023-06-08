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

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Handler.Wrapper} that fires events during the processing of the requests.</p>
 * <p>EventsHandler will emit events for the various phases the server goes through while
 * processing an HTTP request and response.</p>
 * <p>Subclasses may listen to those events to track
 * timing and/or other values such as request URI, etc.</p>
 * <p>The events parameters, especially the {@link Request} object, may be
 * in a transient state depending on the event, and not all properties/features
 * of the parameters may be available inside a listener method.</p>
 * <p>It is recommended that the event parameters are <em>not</em> acted upon
 * in the listener methods, or undefined behavior may result. For example, it
 * would be a bad idea to try to read some content from the
 * {@link Request#read()} in listener methods. On the other
 * hand, it is legit to store request attributes in one listener method that
 * may be possibly retrieved in another listener method in a later event.</p>
 * <p>Listener methods are invoked synchronously from the thread that is
 * performing the request processing, and they should not call blocking code
 * (otherwise the request processing will be blocked as well).</p>
 *
 * <p>The kind of chunk passed to {@link #onRequestRead(Request, Content.Chunk)} depends on the parent of this handler. For
 * instance, if the parent is the Server, then raw chunks are always passed. If somewhere in the parent chain is the
 * {@code GzipHandler} then unzipped chunks are passed.</p>
 */
public abstract class EventsHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(EventsHandler.class);
    
    public EventsHandler()
    {
    }

    public EventsHandler(Handler handler)
    {
        super(handler);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        // (1) Request parsing has begun
         request.getBeginNanoTime();

        // (2) Request header parsing is done
         request.getHeadersNanoTime();

        // Before handling
        ReadOnlyRequest roRequest = new ReadOnlyRequest(request);
        fireOnBeforeHandling(roRequest);
        try
        {
            EventsRequest wrappedRequest = new EventsRequest(request, roRequest);
            EventsResponse wrappedResponse = new EventsResponse(roRequest, response);
            request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream)
            {
                private void fireNotifications(Throwable failure)
                {
                    if (!wrappedResponse.notifiedOnResponseBegin)
                        fireOnResponseBegin(roRequest, wrappedResponse.getHeaders().asImmutable(), wrappedResponse.getStatus());
                    if (wrappedResponse.suppliedTrailers != null)
                        fireOnResponseTrailersComplete(roRequest, wrappedResponse.suppliedTrailers);
                    fireOnComplete(roRequest, failure);
                }

                @Override
                public void succeeded()
                {
                    fireNotifications(null);
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    fireNotifications(x);
                    super.failed(x);
                }
            });

            boolean handled = super.handle(wrappedRequest, wrappedResponse, callback);

            fireOnAfterHandling(roRequest, handled, null);
            return handled;
        }
        catch (Throwable x)
        {
            fireOnAfterHandling(roRequest, false, x);
            throw x;
        }
    }

    private void fireOnBeforeHandling(Request request)
    {
        try
        {
            onBeforeHandling(request);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onBeforeHandling", x);
        }
    }

    private void fireOnRequestRead(Request wrapped, Content.Chunk chunk)
    {
        try
        {
            onRequestRead(wrapped, chunk);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onRequestRead", x);
        }
    }

    private void fireOnAfterHandling(Request request, boolean handled, Throwable failure)
    {
        try
        {
            onAfterHandling(request, handled, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onAfterHandling", x);
        }
    }

    private void fireOnResponseBegin(Request request, HttpFields response, int status)
    {
        try
        {
            onResponseBegin(request, status, response);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseBegin", x);
        }
    }

    private void fireOnResponseWrite(Request request, boolean last, ByteBuffer content)
    {
        try
        {
            onResponseWrite(request, last, content);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseWrite", x);
        }
    }

    private void fireOnResponseWriteComplete(Request request, Throwable failure)
    {
        try
        {
            onResponseWriteComplete(request, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseWriteComplete", x);
        }
    }

    private void fireOnResponseTrailersComplete(Request request, HttpFields trailers)
    {
        try
        {
            onResponseTrailersComplete(request, trailers);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseTrailersComplete", x);
        }
    }

    private void fireOnComplete(Request request, Throwable failure)
    {
        try
        {
            onComplete(request, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onComplete", x);
        }
    }

    /**
     * Invoked just before calling the server handler tree (i.e. just before the {@link Runnable}
     * returned from {@link org.eclipse.jetty.server.HttpChannel#onRequest(MetaData.Request)} is run).
     *
     * <p>
     * This is the final state of the request before the handlers are called.
     * This includes any request customization.
     * </p>
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @see org.eclipse.jetty.server.HttpChannel#onRequest(MetaData.Request)
     */
    protected void onBeforeHandling(Request request)
    {
        // (3): Request started (being sent to handler tree) - request has now been fully customized
    }

    /**
     * Invoked every time a request content chunk has been parsed, just before
     * making it available to the application (i.e. from within a call to
     * {@link Request#read()}).
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param chunk a potentially null request content chunk, including {@link org.eclipse.jetty.io.Content.Chunk.Error}
     *              and {@link org.eclipse.jetty.http.Trailers} chunks.
     *              If a reference to the chunk (or its {@link ByteBuffer}) is kept,
     *              then {@link Content.Chunk#retain()} must be called.
     * @see Request#read()
     */
    protected void onRequestRead(Request request, Content.Chunk chunk)
    {
        // (4) Request body is being read
        // (5) Request trailers are being read from network (chunk instanceof Trailers)
        // (6) Request complete - all Request content (body, trailers) have been read from network (chunk.isLast()))
    }

    /**
     * Invoked after application handling (i.e. just after the call to the {@link Runnable} returned from
     * {@link org.eclipse.jetty.server.HttpChannel#onRequest(MetaData.Request)} returns).
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param handled if the server handlers handled the request
     * @param failure the exception thrown by the application
     * @see org.eclipse.jetty.server.HttpChannel#onRequest(MetaData.Request)
     */
    protected void onAfterHandling(Request request, boolean handled, Throwable failure)
    {
        // (7) Request exit (has exited handler tree)
    }

    /**
     * Invoked just before the response is line written to the network (i.e. from
     * within the first call to {@link Response#write(boolean, ByteBuffer, Callback)}).
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param status the response status
     * @param headers the immutable fields of the response object
     * @see Response#write(boolean, ByteBuffer, Callback)
     */
    protected void onResponseBegin(Request request, int status, HttpFields headers)
    {
        // (8) Response has been committed to network
    }

    /**
     * Invoked before each response content chunk has been written (i.e. from
     * within the any call to {@link Response#write(boolean, ByteBuffer, Callback)}).
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param last indicating last write
     * @param content The {@link ByteBuffer} of the response content chunk (readonly).
     * @see Response#write(boolean, ByteBuffer, Callback)
     */
    protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
    {
        // (9) Response body is being written to network
    }

    /**
     * Invoked after each response content chunk has been written
     * (i.e. immediately before calling the {@link Callback} passed to
     * {@link Response#write(boolean, ByteBuffer, Callback)}).
     * This will always fire <em>before</em> {@link #onResponseTrailersComplete(Request, HttpFields)} is fired.
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param failure if there was a failure to write the given content
     * @see Response#write(boolean, ByteBuffer, Callback)
     */
    protected void onResponseWriteComplete(Request request, Throwable failure)
    {
        // (N/A) Response body buffer was written to the network
    }

    /**
     * Invoked after the response trailers have been written <em>and</em> the final {@link #onResponseWriteComplete(Request, Throwable)} event was fired.
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param trailers the written trailers.
     */
    protected void onResponseTrailersComplete(Request request, HttpFields trailers)
    {
        // (10) Response trailers are being written to network
        // (11) Response complete - all response content (headers, body, trailers) have been written to network (if trailers)
    }

    /**
     * Invoked when the request <em>and</em> response processing are complete,
     * just before the request and response will be recycled (i.e. after the
     * {@link Runnable} return from {@link org.eclipse.jetty.server.HttpChannel#onRequest(MetaData.Request)}
     * has returned and the {@link Callback} passed to {@link Handler#handle(Request, Response, Callback)}
     * has been completed).
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param failure if there was a failure to complete
     */
    protected void onComplete(Request request, Throwable failure)
    {
        // (11) Response complete - all response content (headers, body, trailers) have been written to network (if no trailers
        // (12) Request / Response exchange is complete - request related resources will now be recycled
    }

    private class EventsResponse extends Response.Wrapper
    {
        private boolean notifiedOnResponseBegin;
        private HttpFields suppliedTrailers;

        public EventsResponse(ReadOnlyRequest request, Response response)
        {
            super(request, response);
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (!notifiedOnResponseBegin)
            {
                fireOnResponseBegin(getRequest(), getWrapped().getHeaders().asImmutable(), getWrapped().getStatus());
                notifiedOnResponseBegin = true;
            }
            fireOnResponseWrite(getRequest(), last, byteBuffer);
            super.write(last, byteBuffer, Callback.from(callback, (x) -> fireOnResponseWriteComplete(getRequest(), x)));
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
            super.setTrailersSupplier(trailers == null ? null : () -> suppliedTrailers = trailers.get());
        }
    }

    private class EventsRequest extends Request.Wrapper
    {
        private final ReadOnlyRequest roRequest;

        public EventsRequest(Request request, ReadOnlyRequest roRequest)
        {
            super(request);
            this.roRequest = roRequest;
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = super.read();
            fireOnRequestRead(roRequest, chunk);
            return chunk;
        }
    }

    private static class ReadOnlyRequest extends Request.Wrapper
    {
        private ReadOnlyRequest(Request request)
        {
            super(request);
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream> wrapper)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Content.Chunk read()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(Throwable failure)
        {
            throw new UnsupportedOperationException();
        }
    }
}
