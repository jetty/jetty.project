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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;

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
     * <p>Returns the headers of this response.</p>
     * <p>Some headers sent by the server may not be present,
     * or be present but modified, while the content is being
     * processed.
     * A typical example is the {@code Content-Length} header
     * when the content is sent compressed by the server and
     * automatically decompressed by the client: the
     * {@code Content-Length} header will be removed.</p>
     * <p>Similarly, the {@code Content-Encoding} header
     * may be removed or modified, as the content is
     * decoded by the client.</p>
     *
     * @return the headers of this response
     */
    HttpFields getHeaders();

    /**
     * @return the trailers of this response
     */
    HttpFields getTrailers();

    /**
     * Attempts to abort the receive of this response.
     *
     * @param cause the abort cause, must not be null
     * @return whether the abort succeeded
     */
    CompletableFuture<Boolean> abort(Throwable cause);

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
         * This method is also always invoked when content arrives as demand is automatically registered on return.
         *
         * @param response the response containing the response line data and the headers
         * @param content the content bytes received
         * @throws Exception an uncaught exception will abort the response and eventually reclaim the ByteBuffer, if applicable
         */
        void onContent(Response response, ByteBuffer content) throws Exception;

        @Override
        default void onContent(Response response, Content.Chunk chunk, Runnable demander) throws Exception
        {
            onContent(response, chunk.getByteBuffer());
            demander.run();
        }
    }

    /**
     * Asynchronous listener for the response content events.
     *
     * @see ContentSourceListener
     */
    interface AsyncContentListener extends ContentSourceListener
    {
        /**
         * Callback method invoked when the response content has been received, parsed and there is demand.
         * The {@code chunk} must be consumed, copied, or retained before returning from this method as
         * it is then automatically released.
         * The {@code demander} must be run before this method may be invoked again.
         *
         * @param response the response containing the response line data and the headers
         * @param chunk the chunk received
         * @param demander the runnable to be run to demand the next chunk
         * @throws Exception an uncaught exception will abort the response, release the chunk and fail the content source
         * from which the chunk was read from
         */
        void onContent(Response response, Content.Chunk chunk, Runnable demander) throws Exception;

        @Override
        default void onContentSource(Response response, Content.Source contentSource)
        {
            Content.Chunk chunk = contentSource.read();
            // demandCallback eventually calls onContent() which calls end-user code, so its InvocationType must be BLOCKING.
            Runnable demandCallback = () -> onContentSource(response, contentSource);
            if (chunk == null)
            {
                contentSource.demand(demandCallback);
                return;
            }
            if (Content.Chunk.isFailure(chunk))
            {
                response.abort(chunk.getFailure());
                if (!chunk.isLast())
                    contentSource.fail(chunk.getFailure());
                return;
            }
            if (chunk.isLast() && !chunk.hasRemaining())
            {
                chunk.release();
                return;
            }

            try
            {
                onContent(response, chunk, () -> contentSource.demand(demandCallback));
                chunk.release();
            }
            catch (Throwable x)
            {
                chunk.release();
                response.abort(x);
                contentSource.fail(x);
            }
        }
    }

    /**
     * Asynchronous listener for the response content events.
     */
    interface ContentSourceListener extends ResponseListener
    {
        /**
         * Callback method invoked when all the response headers have been received and parsed.
         * It is responsible for driving the {@code contentSource} instance with a read/demand loop.
         * Note that this is not invoked for interim statuses.
         *
         * @param response the response containing the response line data and the headers
         * @param contentSource the {@link Content.Source} that must be driven to read the data
         */
        void onContentSource(Response response, Content.Source contentSource);
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
    }
}
