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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.internal.HttpChannelState;
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
    public interface Listener extends EventListener
    {
        /**
         * Invoked just after the HTTP request line and headers have been parsed.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onRequestBegin(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked just before calling the application.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onBeforeDispatch(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked when the application threw an exception.
         *
         * @param request the request object
         * @param response the response object
         * @param failure the exception thrown by the application
         */
        default void onDispatchFailure(Request request, Response response, Throwable failure)
        {
            // done
        }

        /**
         * Invoked just after the application returns from the first invocation.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onAfterDispatch(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked every time a request content chunk has been parsed, just before
         * making it available to the application.
         *
         * @param request the request object
         * @param response the response object
         * @param content a {@link ByteBuffer#slice() slice} of the request content chunk
         */
        default void onRequestContent(Request request, Response response, ByteBuffer content)
        {
            // done
        }

        /**
         * Invoked when the end of the request content is detected.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onRequestContentEnd(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked when the request trailers have been parsed.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onRequestTrailers(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked when the request has been fully parsed.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onRequestEnd(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked when the request processing failed.
         *
         * @param request the request object
         * @param response the response object
         * @param failure the request failure
         */
        default void onRequestFailure(Request request, Response response, Throwable failure)
        {
            // done
        }

        /**
         * Invoked just before the response line is written to the network.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onResponseBegin(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked just after the response is committed (that is, the response
         * line, headers and possibly some content have been written to the
         * network).
         *
         * @param request the request object
         * @param response the response object
         */
        default void onResponseCommit(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked after a response content chunk has been written to the network.
         *
         * @param request the request object
         * @param response the response object
         * @param content a {@link ByteBuffer#slice() slice} of the response content chunk
         */
        default void onResponseContent(Request request, Response response, ByteBuffer content)
        {
            // done
        }

        /**
         * Invoked when the response has been fully written.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onResponseEnd(Request request, Response response)
        {
            // done
        }

        /**
         * Invoked when the response processing failed.
         *
         * @param request the request object
         * @param response the response object
         * @param failure the response failure
         */
        default void onResponseFailure(Request request, Response response, Throwable failure)
        {
            // done
        }

        /**
         * Invoked when the request <em>and</em> response processing are complete.
         *
         * @param request the request object
         * @param response the response object
         */
        default void onComplete(Request request, Response response)
        {
            // done
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
