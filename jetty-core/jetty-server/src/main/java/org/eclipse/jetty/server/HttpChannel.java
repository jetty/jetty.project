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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.util.EventListener;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>Represents the state of an HTTP request/response cycle.</p>
 * <p>{@code HttpChannel} links the lower (closer to the network) layer {@link HttpStream}
 * with the upper (application code) layer {@link Handler}.</p>
 * <p>An {@code HttpChannel} instance may be used for many HTTP request/response cycles
 * from the same connection; however, only a single cycle may be active at any time.</p>
 * <p>Default implementations of this interface may be created via {@link DefaultFactory}.</p>
 */
public interface HttpChannel extends Invocable
{
    /**
     * @return the {@code ConnectionMetaData} associated with this channel.
     */
    ConnectionMetaData getConnectionMetaData();

    /**
     * @param httpStream the {@link HttpStream} to associate to this channel.
     */
    void setHttpStream(HttpStream httpStream);

    /**
     * @return whether the request has been passed to the root {@link Handler}.
     */
    boolean isRequestHandled();

    /**
     * <p>{@link HttpStream} invokes this method when the metadata of an HTTP
     * request (method, URI and headers, but not content) has been parsed.</p>
     * <p>The returned {@code Runnable} invokes the root {@link Handler}.</p>
     *
     * @param metaData the HTTP request metadata.
     * @return a {@code Runnable} that invokes the root {@link Handler}.
     */
    Runnable onRequest(MetaData.Request metaData);

    /**
     * <p>Returns the {@link Request} object, if available.</p>
     * <p>The {@code Request} object is only available after a call to
     * {@link #onRequest(MetaData.Request)} has been made.</p>
     *
     * @return the {@code Request} object, or null if the {@code Request} object
     * is not yet available.
     */
    Request getRequest();

    /**
     * <p>{@link HttpStream} invokes this method when more HTTP request content is available.</p>
     *
     * @return the last {@code Runnable} passed to {@link Request#demand(Runnable)},
     * or {@code null} if there is no demand for content.
     */
    Runnable onContentAvailable();

    /**
     * <p>Notifies this {@code HttpChannel} that an asynchronous failure happened.</p>
     * <p>Typical failure examples could be idle timeouts, I/O read failures or
     * protocol failures (for example, invalid request bytes).</p>
     *
     * @param failure the failure cause.
     * @return a {@code Runnable} that performs the failure action, or {@code null}
     * if no failure action should be performed by the caller thread
     */
    Runnable onFailure(Throwable failure);

    /**
     * Recycle the HttpChannel, so that a new cycle of calling {@link #setHttpStream(HttpStream)},
     * {@link #onRequest(MetaData.Request)} etc. may be performed on the channel.
     */
    void recycle();

    /**
     * <p>A factory for {@link HttpChannel} instances.</p>
     *
     * @see DefaultFactory
     */
    interface Factory
    {
        /**
         * @param connectionMetaData the {@code ConnectionMetaData} associated with the channel.
         * @return a new {@link HttpChannel} instance.
         */
        HttpChannel newHttpChannel(ConnectionMetaData connectionMetaData);
    }

    /**
     * <p>Listener for {@link HttpChannel} events.</p>
     * <p>HttpChannel will emit events for the various phases it goes through while
     * processing an HTTP request and response.</p>
     * <p>Implementations of this interface may listen to those events to track
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
     * <p>Listener instances that are set as a bean on the {@link Connector} are
     * efficiently added to {@link HttpChannel}.
     */
    interface Listener extends EventListener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed
         * (i.e. from within the call to {@link HttpChannel#onRequest(MetaData.Request)}).
         *
         * <p>
         * This the state of the request from the network, and does not include
         * any request customizations (eg: forwarding, secure, etc)
         * </p>
         *
         * @param request the request object, which should not be mutated in any way.
         * @see HttpChannel#onRequest(MetaData.Request)
         */
        default void onRequestBegin(Request request)
        {
        }

        /**
         * Invoked just before calling the server handler tree (i.e. just before the {@link Runnable}
         * returned from {@link HttpChannel#onRequest(MetaData.Request)} is run).
         *
         * <p>
         * This is the final state of the request before the handlers are called.
         * This includes any request customization.
         * </p>
         *
         * @param request the request object, which should not be mutated in any way.
         * @see HttpChannel#onRequest(MetaData.Request)
         */
        default void onBeforeHandling(Request request)
        {
        }

        /**
         * Invoked after application handling (i.e. just after the call to the {@link Runnable} returned from
         * {@link HttpChannel#onRequest(MetaData.Request)} returns).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param handled if the server handlers handled the request
         * @param failure the exception thrown by the application
         * @see HttpChannel#onRequest(MetaData.Request)
         */
        default void onAfterHandling(Request request, boolean handled, Throwable failure)
        {
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application (i.e. from within a call to
         * {@link Request#read()}).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param chunk a request content chunk, including {@link org.eclipse.jetty.io.Content.Chunk.Error}
         *              and {@link org.eclipse.jetty.http.Trailers} chunks.
         *              If a reference to the chunk (or its {@link ByteBuffer}) is kept,
         *              then {@link Content.Chunk#retain()} must be called.
         * @see Request#read()
         */
        default void onRequestRead(Request request, Content.Chunk chunk)
        {
        }

        /**
         * Invoked just before the response is line written to the network (i.e. from
         * within the first call to {@link Response#write(boolean, ByteBuffer, Callback)}).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param status the response status
         * @param response the immutable fields of the response object
         * @see Response#write(boolean, ByteBuffer, Callback)
         */
        default void onResponseCommitted(Request request, int status, HttpFields response)
        {
        }

        /**
         * Invoked before each response content chunk has been written (i.e. from
         * within the any call to {@link Response#write(boolean, ByteBuffer, Callback)}).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param last indicating last write
         * @param content The {@link ByteBuffer} of the response content chunk (readonly).
         * @see Response#write(boolean, ByteBuffer, Callback)
         */
        default void onResponseWrite(Request request, boolean last, ByteBuffer content)
        {
        }

        /**
         * Invoked after each response content chunk has been written
         * (i.e. immediately before calling the {@link Callback} passed to
         * {@link Response#write(boolean, ByteBuffer, Callback)}).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param failure if there was a failure to write the given content
         * @see Response#write(boolean, ByteBuffer, Callback)
         */
        default void onResponseWriteComplete(Request request, Throwable failure)
        {
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete,
         * just before the request and response will be recycled (i.e. after the
         * {@link Runnable} return from {@link HttpChannel#onRequest(MetaData.Request)}
         * has returned and the {@link Callback} passed to {@link Handler#handle(Request, Response, Callback)}
         * has been completed).
         *
         * @param request the request object, which should not be mutated in any way.
         * @param failure if there was a failure to complete
         */
        default void onComplete(Request request, Throwable failure)
        {
        }
    }

    /**
     * <p>The factory that creates default implementations of {@link HttpChannel}.</p>
     */
    class DefaultFactory implements Factory
    {
        @Override
        public HttpChannel newHttpChannel(ConnectionMetaData connectionMetaData)
        {
            return new HttpChannelState(connectionMetaData);
        }
    }
}
