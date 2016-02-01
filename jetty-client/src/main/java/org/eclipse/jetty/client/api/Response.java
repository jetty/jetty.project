//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;

/**
 * <p>{@link Response} represents a HTTP response and offers methods to retrieve status code, HTTP version
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
     * @return the response listener passed to {@link org.eclipse.jetty.client.api.Request#send(org.eclipse.jetty.client.api.Response.CompleteListener)}
     * @param <T> the type of class
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
    public interface ResponseListener extends EventListener
    {
    }

    /**
     * Listener for the response begin event.
     */
    public interface BeginListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response line containing HTTP version,
         * HTTP status code and reason has been received and parsed.
         * <p>
         * This method is the best approximation to detect when the first bytes of the response arrived to the client.
         *
         * @param response the response containing the response line data
         */
        public void onBegin(Response response);
    }

    /**
     * Listener for a response header event.
     */
    public interface HeaderListener extends ResponseListener
    {
        /**
         * Callback method invoked when a response header has been received,
         * returning whether the header should be processed or not.
         *
         * @param response the response containing the response line data and the headers so far
         * @param field the header received
         * @return true to process the header, false to skip processing of the header
         */
        public boolean onHeader(Response response, HttpField field);
    }

    /**
     * Listener for the response headers event.
     */
    public interface HeadersListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response headers have been received and parsed.
         *
         * @param response the response containing the response line data and the headers
         */
        public void onHeaders(Response response);
    }

    /**
     * Listener for the response content events.
     */
    public interface ContentListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response content has been received.
         * This method may be invoked multiple times, and the {@code content} buffer must be consumed
         * before returning from this method.
         *
         * @param response the response containing the response line data and the headers
         * @param content the content bytes received
         */
        public void onContent(Response response, ByteBuffer content);
    }

    public interface AsyncContentListener extends ResponseListener
    {
        /**
         * Callback method invoked asynchronously when the response content has been received.
         *
         * @param response the response containing the response line data and the headers
         * @param content the content bytes received
         * @param callback the callback to call when the content is consumed.
         */
        public void onContent(Response response, ByteBuffer content, Callback callback);
    }

    /**
     * Listener for the response succeeded event.
     */
    public interface SuccessListener extends ResponseListener
    {
        /**
         * Callback method invoked when the whole response has been successfully received.
         *
         * @param response the response containing the response line data and the headers
         */
        public void onSuccess(Response response);
    }

    /**
     * Listener for the response failure event.
     */
    public interface FailureListener extends ResponseListener
    {
        /**
         * Callback method invoked when the response has failed in the process of being received
         *
         * @param response the response containing data up to the point the failure happened
         * @param failure the failure happened
         */
        public void onFailure(Response response, Throwable failure);
    }

    /**
     * Listener for the request and response completed event.
     */
    public interface CompleteListener extends ResponseListener
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
        public void onComplete(Result result);
    }

    /**
     * Listener for all response events.
     */
    public interface Listener extends BeginListener, HeaderListener, HeadersListener, ContentListener, AsyncContentListener, SuccessListener, FailureListener, CompleteListener
    {
        /**
         * An empty implementation of {@link Listener}
         */
        public static class Adapter implements Listener
        {
            @Override
            public void onBegin(Response response)
            {
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                return true;
            }

            @Override
            public void onHeaders(Response response)
            {
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
            }

            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
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

            @Override
            public void onSuccess(Response response)
            {
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
            }

            @Override
            public void onComplete(Result result)
            {
            }
        }
    }
}
