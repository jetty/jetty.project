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

package org.eclipse.jetty.client.api;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.function.LongConsumer;

import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;

/**
 * <p>{@link Response} represents an HTTP response and offers methods to retrieve status code, HTTP version
 * and headers.</p>
 * <p>{@link Response} objects are passed as parameters to {@link Response.Listener} callbacks, or as
 * future result of {@link Request#send()}.</p>
 * <p>{@link Response} objects do not contain getters for the response content, because it may be too large
 * to fit into memory.
 * The response content should be retrieved via {@link Response.Listener#onContent(Response, ByteBuffer) content
 * events}, or via utility classes such as {@link BufferingResponseListener}.</p>
 */
public interface Response
{
    /**
     * @return the request associated with this response
     */
    Request getRequest();

    /**
     * @param listenerClass the listener class
     * @param <T> the type of class
     * @return the response listener passed to {@link org.eclipse.jetty.client.api.Request#send(org.eclipse.jetty.client.api.Response.CompleteListener)}
     */
    <T extends ResponseListener> List<T> getListeners(Class<T> listenerClass);

    /**
     * @return the HTTP version of this response, such as "HTTP/1.1"
     */
    HttpVersion getVersion();

    /**
     * @return the HTTP status code of this response, such as 200 or 404
     */
    int getStatus();

    /**
     * @return the HTTP reason associated to the {@link #getStatus}
     */
    String getReason();

    /**
     * @return the headers of this response
     */
    HttpFields getHeaders();

    /**
     * Attempts to abort the receive of this response.
     *
     * @param cause the abort cause, must not be null
     * @return whether the abort succeeded
     */
    boolean abort(Throwable cause);

    /**
     * Common, empty, super-interface for response listeners
     */
    interface ResponseListener extends EventListener
    {
    }

    /**
     * Listener for the response begin event.
     */
    interface BeginListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response line containing HTTP version,
         * HTTP status code and reason has been received and parsed.
         * <p>
         * This method is the best approximation to detect when the first bytes of the response arrived to the client.
         *
         * @param response the response containing the response line data
         */
        void onBegin(Response response);
    }

    /**
     * Listener for a response header event.
     */
    interface HeaderListener extends ResponseListener
    {
        /**
         * Callback method invoked when a response header has been received and parsed,
         * returning whether the header should be processed or not.
         *
         * @param response the response containing the response line data and the headers so far
         * @param field the header received
         * @return true to process the header, false to skip processing of the header
         */
        boolean onHeader(Response response, HttpField field);
    }

    /**
     * Listener for the response headers event.
     */
    interface HeadersListener extends ResponseListener
    {
        /**
         * Callback method invoked when all the response headers have been received and parsed.
         *
         * @param response the response containing the response line data and the headers
         */
        void onHeaders(Response response);
    }

    /**
     * Synchronous listener for the response content events.
     *
     * @see AsyncContentListener
     */
    interface ContentListener extends AsyncContentListener
    {
        /**
         * Callback method invoked when the response content has been received, parsed and there is demand.
         * This method may be invoked multiple times, and the {@code content} buffer
         * must be consumed (or copied) before returning from this method.
         *
         * @param response the response containing the response line data and the headers
         * @param content the content bytes received
         */
        void onContent(Response response, ByteBuffer content);

        @Override
        default void onContent(Response response, ByteBuffer content, Callback callback)
        {
            try
            {
                onContent(response, content);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }
    }

    /**
     * Asynchronous listener for the response content events.
     *
     * @see DemandedContentListener
     */
    interface AsyncContentListener extends DemandedContentListener
    {
        /**
         * Callback method invoked when the response content has been received, parsed and there is demand.
         * The {@code callback} object should be succeeded to signal that the
         * {@code content} buffer has been consumed and to demand more content.
         *
         * @param response the response containing the response line data and the headers
         * @param content the content bytes received
         * @param callback the callback to call when the content is consumed and to demand more content
         */
        void onContent(Response response, ByteBuffer content, Callback callback);

        @Override
        default void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
        {
            onContent(response, content, Callback.from(() ->
            {
                callback.succeeded();
                demand.accept(1);
            }, callback::failed));
        }
    }

    /**
     * Asynchronous listener for the response content events.
     */
    interface DemandedContentListener extends ResponseListener
    {
        /**
         * Callback method invoked before response content events.
         * The {@code demand} object should be used to demand content, otherwise
         * the demand remains at zero (no demand) and
         * {@link #onContent(Response, LongConsumer, ByteBuffer, Callback)} will
         * not be invoked even if content has been received and parsed.
         *
         * @param response the response containing the response line data and the headers
         * @param demand the object that allows to demand content buffers
         */
        default void onBeforeContent(Response response, LongConsumer demand)
        {
            demand.accept(1);
        }

        /**
         * Callback method invoked when the response content has been received.
         * The {@code callback} object should be succeeded to signal that the
         * {@code content} buffer has been consumed.
         * The {@code demand} object should be used to demand more content,
         * similarly to ReactiveStreams's {@code Subscription#request(long)}.
         *
         * @param response the response containing the response line data and the headers
         * @param demand the object that allows to demand content buffers
         * @param content the content bytes received
         * @param callback the callback to call when the content is consumed
         */
        void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback);
    }

    /**
     * Listener for the response succeeded event.
     */
    interface SuccessListener extends ResponseListener
    {
        /**
         * Callback method invoked when the whole response has been successfully received.
         *
         * @param response the response containing the response line data and the headers
         */
        void onSuccess(Response response);
    }

    /**
     * Listener for the response failure event.
     */
    interface FailureListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response has failed in the process of being received
         *
         * @param response the response containing data up to the point the failure happened
         * @param failure the failure happened
         */
        void onFailure(Response response, Throwable failure);
    }

    /**
     * Listener for the request and response completed event.
     */
    interface CompleteListener extends ResponseListener
    {
        /**
         * Callback method invoked when the request <em><b>and</b></em> the response have been processed,
         * either successfully or not.
         * <p>
         * The {@code result} parameter contains the request, the response, and eventual failures.
         * <p>
         * Requests may complete <em>after</em> response, for example in case of big uploads that are
         * discarded or read asynchronously by the server.
         * This method is always invoked <em>after</em> {@link SuccessListener#onSuccess(Response)} or
         * {@link FailureListener#onFailure(Response, Throwable)}, and only when request indicates that
         * it is completed.
         *
         * @param result the result of the request / response exchange
         */
        void onComplete(Result result);
    }

    /**
     * Listener for all response events.
     */
    interface Listener extends BeginListener, HeaderListener, HeadersListener, ContentListener, SuccessListener, FailureListener, CompleteListener
    {
        @Override
        public default void onBegin(Response response)
        {
        }

        @Override
        public default boolean onHeader(Response response, HttpField field)
        {
            return true;
        }

        @Override
        public default void onHeaders(Response response)
        {
        }

        @Override
        public default void onContent(Response response, ByteBuffer content)
        {
        }

        @Override
        public default void onSuccess(Response response)
        {
        }

        @Override
        public default void onFailure(Response response, Throwable failure)
        {
        }

        @Override
        public default void onComplete(Result result)
        {
        }

        /**
         * An empty implementation of {@link Listener}
         */
        class Adapter implements Listener
        {
        }
    }
}
