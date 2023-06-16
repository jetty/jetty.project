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
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Handler.Wrapper} that fires events during the processing of the requests.</p>
 * <p>EventsHandler will emit events for the various phases the server goes through while
 * processing an HTTP request and response.</p>
 * <p>Subclasses may listen to those events to track timing and/or other values such as
 * request URI, etc.</p>
 * <p>The events parameters, especially the {@link Request} object, may be
 * in a transient state depending on the event, and not all properties/features
 * of the parameters may be available inside a listener method.</p>
 * <p>It is recommended that the event parameters are <em>not</em> acted upon
 * in the listener methods, or undefined behavior may result. On the other
 * hand, it is legit to store request attributes in one listener method that
 * may be possibly retrieved in another listener method in a later event.</p>
 * <p>Listener methods are invoked synchronously from the thread that is
 * performing the request processing, and they should not call blocking code
 * (otherwise the request processing will be blocked as well).</p>
 * <p>The kind of chunk passed to {@link #onRequestRead(Request, Content.Chunk)} depends on
 * the parent of this handler. For instance, if the parent is the Server, then raw chunks
 * are always passed. If somewhere in the parent chain is the {@code GzipHandler} then
 * unzipped chunks are passed.</p>
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
        Request roRequest = Request.asReadOnly(request);
        notifyOnBeforeHandling(roRequest);
        try
        {
            EventsRequest wrappedRequest = new EventsRequest(request, roRequest);
            EventsResponse wrappedResponse = new EventsResponse(roRequest, response);
            request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream)
            {
                @Override
                public void succeeded()
                {
                    notifyOnResponseBegin(roRequest, wrappedResponse);
                    notifyOnResponseTrailersComplete(roRequest, wrappedResponse);
                    notifyOnComplete(roRequest, null);
                    super.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    notifyOnResponseBegin(roRequest, wrappedResponse);
                    notifyOnResponseTrailersComplete(roRequest, wrappedResponse);
                    notifyOnComplete(roRequest, x);
                    super.failed(x);
                }
            });

            boolean handled = super.handle(wrappedRequest, wrappedResponse, callback);

            notifyOnAfterHandling(roRequest, handled, null);
            return handled;
        }
        catch (Throwable x)
        {
            notifyOnAfterHandling(roRequest, false, x);
            throw x;
        }
    }

    private void notifyOnBeforeHandling(Request request)
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

    private void notifyOnRequestRead(Request wrapped, Content.Chunk chunk)
    {
        try
        {
            onRequestRead(wrapped, chunk == null ? null : chunk.asReadOnly());
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onRequestRead", x);
        }
    }

    private void notifyOnAfterHandling(Request request, boolean handled, Throwable failure)
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

    private void notifyOnResponseBegin(Request request, EventsResponse response)
    {
        try
        {
            if (!response.notifiedOnResponseBegin)
            {
                onResponseBegin(request, response.getStatus(), response.getHeaders().asImmutable());
                response.notifiedOnResponseBegin = true;
            }
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseBegin", x);
        }
    }

    private void notifyOnResponseWrite(Request request, boolean last, ByteBuffer content)
    {
        try
        {
            onResponseWrite(request, last, content == null ? null : content.asReadOnlyBuffer());
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseWrite", x);
        }
    }

    private void notifyOnResponseWriteComplete(Request request, Throwable failure)
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

    private void notifyOnResponseTrailersComplete(Request request, EventsResponse response)
    {
        try
        {
            if (response.suppliedTrailers != null)
                onResponseTrailersComplete(request, response.suppliedTrailers);
        }
        catch (Throwable x)
        {
            LOG.info("Error firing onResponseTrailersComplete", x);
        }
    }

    private void notifyOnComplete(Request request, Throwable failure)
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
        if (LOG.isDebugEnabled())
            LOG.debug("onBeforeHandling of {}", request);
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
        if (LOG.isDebugEnabled())
            LOG.debug("onRequestRead of {} and {}", request, chunk);
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
        if (LOG.isDebugEnabled())
            LOG.debug("onAfterHandling of {} handled={}", request, handled, failure);
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
        if (LOG.isDebugEnabled())
            LOG.debug("onResponseBegin of {} status={} headers={}", request, status, headers);
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
        if (LOG.isDebugEnabled())
            LOG.debug("onResponseWrite of {} last={} content={}", request, last, BufferUtil.toDetailString(content));
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
        if (LOG.isDebugEnabled())
            LOG.debug("onResponseWriteComplete of {}", request, failure);
    }

    /**
     * Invoked after the response trailers have been written <em>and</em> the final {@link #onResponseWriteComplete(Request, Throwable)} event was fired.
     *
     * @param request the request object. The {@code read()}, {@code demand(Runnable)} and {@code fail(Throwable)} methods must not be called by the listener.
     * @param trailers the written trailers.
     */
    protected void onResponseTrailersComplete(Request request, HttpFields trailers)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onResponseTrailersComplete of {}, trailers={}", request, trailers);
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
        if (LOG.isDebugEnabled())
            LOG.debug("onComplete of {}", request, failure);
    }

    private class EventsResponse extends Response.Wrapper
    {
        private boolean notifiedOnResponseBegin;
        private HttpFields suppliedTrailers;

        public EventsResponse(Request roRequest, Response response)
        {
            super(roRequest, response);
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            notifyOnResponseBegin(getRequest(), this);
            notifyOnResponseWrite(getRequest(), last, byteBuffer);
            super.write(last, byteBuffer, Callback.from(callback, (x) -> notifyOnResponseWriteComplete(getRequest(), x)));
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
            super.setTrailersSupplier(trailers == null ? null : () -> suppliedTrailers = trailers.get());
        }
    }

    private class EventsRequest extends Request.Wrapper
    {
        private final Request roRequest;

        public EventsRequest(Request request, Request roRequest)
        {
            super(request);
            this.roRequest = roRequest;
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = super.read();
            notifyOnRequestRead(roRequest, chunk);
            return chunk;
        }
    }
}
