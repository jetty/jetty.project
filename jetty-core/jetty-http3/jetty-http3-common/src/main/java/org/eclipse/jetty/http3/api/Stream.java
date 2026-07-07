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

package org.eclipse.jetty.http3.api;

import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.ReadableBuffer;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data within a {@link Session}.</p>
 * <p>A {@link Stream} maps to an HTTP/3 request/response cycle, and after the request/response
 * cycle is completed, the stream is closed and removed from the {@link Session}.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link Stream.Client.Listener} and
 * {@link Stream.Server.Listener} are the passive part, and their callbacks are invoked when
 * events happen on the stream.</p>
 * <p>The client initiates a stream by sending a HEADERS frame containing the HTTP/3 request URI
 * and request headers, and zero or more DATA frames containing request content.</p>
 * <p>Similarly, the server responds by sending a HEADERS frame containing the HTTP/3 response
 * status code and response headers, and zero or more DATA frames containing response content.</p>
 * <p>Both client and server can end their side of the stream by sending a final frame with
 * the {@code last} flag set to {@code true}, see {@link HeadersFrame#HeadersFrame(MetaData, boolean)}
 * and {@link DataFrame#DataFrame(ReadableBuffer, boolean)}.</p>
 */
public interface Stream
{
    /**
     * Get the stream id.
     * @return the stream id
     */
    long getId();

    /**
     * Get the session this stream is associated to.
     * @return the session this stream is associated to
     */
    Session getSession();

    /**
     * <p>Sends the given DATA frame containing some or all the bytes
     * of the request content or of the response content.</p>
     *
     * @param frame the DATA frame containing some or all the bytes of the request or of the response.
     * @param promise the {@link Promise.Invocable} that gets notified when the frame has been sent
     */
    void data(DataFrame frame, Promise.Invocable<Stream> promise);

    /**
     * <p>Reads request content bytes or response content bytes.</p>
     * <p>The returned {@link Content.Chunk} object may be {@code null}, indicating
     * that the end of the read side of the stream has not yet been reached, which
     * may happen in these cases:</p>
     * <ul>
     *   <li>not all the bytes have been received so far, for example the remote
     *   peer did not send them yet, or they are in-flight</li>
     *   <li>all the bytes have been received, but there is a trailer HEADERS
     *   frame to be received to indicate the end of the read side of the
     *   stream</li>
     * </ul>
     * <p>When the returned {@link Content.Chunk} object is not {@code null},
     * applications <em>must</em> call, either immediately or later (possibly
     * asynchronously) {@link Content.Chunk#release()} to notify the
     * implementation that the bytes have been processed.</p>
     * <p>{@link Content.Chunk} objects may be stored away for later, asynchronous,
     * processing (for example, to process them only when all of them have been
     * received).</p>
     *
     * @return a {@link Content.Chunk} object containing the request bytes or
     * the response bytes or a failure, or null if no bytes are available
     * @see Stream.Client.Listener#onDataAvailable(Stream.Client, boolean)
     * @see Stream.Server.Listener#onDataAvailable(Stream.Server, boolean)
     */
    Content.Chunk read();

    /**
     * <p>Demands more {@code DATA} frames for this stream.</p>
     * <p>Calling this method causes {@link Stream.Client.Listener#onDataAvailable(Stream.Client, boolean)}
     * on the client, or {@link Stream.Server.Listener#onDataAvailable(Stream.Server, boolean)}
     * on the server, to be invoked, possibly at a later time, when the stream
     * has data to be read, but also when the stream has reached EOF.</p>
     * <p>This method is idempotent: calling it when there already is an
     * outstanding demand to invoke {@code onDataAvailable(Stream)}
     * is a no-operation.</p>
     * <p>The thread invoking this method may invoke directly
     * {@code onDataAvailable(Stream)}, unless another thread
     * that must invoke {@code onDataAvailable(Stream)}
     * notices the outstanding demand first.</p>
     * <p>It is always guaranteed that invoking this method from within
     * {@code onDataAvailable(Stream)} will not cause a
     * {@link StackOverflowError}.</p>
     *
     * @see #read()
     * @see Stream.Client.Listener#onDataAvailable(Stream.Client, boolean)
     * @see Stream.Server.Listener#onDataAvailable(Stream.Server, boolean)
     */
    void demand();

    /**
     * <p>Sends the given HEADERS frame containing the trailer headers.</p>
     *
     * @param frame the HEADERS frame containing the trailer headers
     * @param promise the {@link Promise.Invocable} that gets notified when the frame has been sent
     */
    void trailer(HeadersFrame frame, Promise.Invocable<Stream> promise);

    /**
     * <p>Abruptly terminates this stream with the given error.</p>
     * <p>This method removes this stream from its session and
     * then terminates the QUIC stream, via {@code STOP_SENDING}
     * and {@code RESET} frames, if necessary.</p>
     *
     * @param appErrorCode the error code
     * @param failure the failure that caused the close of the stream, if any
     * @param promise the {@link Promise.Invocable} that gets notified when the disconnect is complete
     */
    void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise);

    /**
     * <p>The client side version of {@link Stream}.</p>
     */
    interface Client extends Stream
    {
        /**
         * <p>A {@link Stream.Client.Listener} is the passive counterpart of a {@link Stream.Client}
         * and receives client-side events happening on an HTTP/3 stream.</p>
         *
         * @see Stream.Client
         */
        interface Listener
        {
            /**
             * <p>Callback method invoked when a stream is created locally by
             * {@link Session.Client#newRequest(HeadersFrame, Listener, Promise.Invocable)}.</p>
             *
             * @param stream the newly created stream
             */
            default void onNewStream(Stream.Client stream)
            {
            }

            /**
             * <p>Callback method invoked when a response is received.</p>
             * <p>To read response content, applications should call
             * {@link Stream#demand()} and override either
             * {@link Stream.Client.Listener#onDataAvailable(Client)} or
             * {@link Stream.Client.Listener#onDataAvailable(Client, boolean)}.</p>
             *
             * @param stream the stream
             * @param frame the HEADERS frame containing the response headers
             * @see Stream.Client.Listener#onDataAvailable(Client, boolean)
             */
            default void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                if (!frame.isLast())
                    stream.demand();
            }

            /**
             * <p>A simplified version of {@link #onDataAvailable(Stream.Client, boolean)}.</p>
             * <p>The default implementation of this method reads and discards data.</p>
             *
             * @param stream the stream
             * @see Stream#demand()
             */
            default void onDataAvailable(Stream.Client stream)
            {
                try
                {
                    while (true)
                    {
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        chunk.release();
                        if (chunk.isLast())
                            return;
                    }
                }
                catch (Throwable x)
                {
                    onFailure(stream, HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
                }
            }

            /**
             * <p>Callback method invoked if the application has expressed
             * {@link Stream#demand() demand} for content, and if there may
             * be content available.</p>
             * <p>A server application that wishes to handle request content
             * should typically call {@link Stream#demand()} from
             * {@link Stream.Server.Listener#onRequest(Server, HeadersFrame)}.</p>
             * <p>A client application that wishes to handle response content
             * should typically call {@link Stream#demand()} from
             * {@link Stream.Client.Listener#onResponse(Client, HeadersFrame)}.</p>
             * <p>Just prior calling this method, the outstanding demand is
             * cancelled; applications that implement this method should read
             * content calling {@link Stream#read()}, and call
             * {@link Stream#demand()} to signal to the implementation to call
             * again this method when there may be more content available.</p>
             * <p>Only one thread at a time invokes this method, although it
             * may not be the same thread across different invocations.</p>
             * <p>It is always guaranteed that invoking {@link Stream#demand()}
             * from within this method will not cause a {@link StackOverflowError}.</p>
             * <p>Typical usage:</p>
             * <pre>{@code
             * class MyStreamListener implements Stream.Client.Listener
             * {
             *     @Override
             *     public void onDataAvailable(Stream.Client stream)
             *     {
             *         // Read a chunk of the content.
             *         Content.Chunk chunk = stream.read();
             *         if (chunk == null)
             *         {
             *             // No data available now, demand to be called back.
             *             stream.demand();
             *         }
             *         else
             *         {
             *             // Process the content chunk.
             *             process(chunk);
             *             // Notify that the content has been consumed.
             *             chunk.release();
             *             if (!chunk.isLast())
             *             {
             *                 // Demand to be called back.
             *                 stream.demand();
             *             }
             *         }
             *     }
             * }
             * }</pre>
             * <p>The default implementation of this method calls
             * {@link #onDataAvailable(Stream.Client)}.</p>
             *
             * @param stream the stream
             * @param immediate {@code true} when data is immediately available at the time
             * {@link #demand()} is invoked (this method is directly invoked from {@link #demand()};
             * {@code false} when data was not immediately available at the time {@link #demand()}
             * was called, but is now available (this method is invoked from the network layer,
             * not directly from {@link #demand()}
             * @see Stream#demand()
             */
            default void onDataAvailable(Stream.Client stream, boolean immediate)
            {
                onDataAvailable(stream);
            }

            /**
             * <p>Callback method invoked when a trailer is received.</p>
             *
             * @param stream the stream
             * @param frame the HEADERS frame containing the trailer headers
             */
            default void onTrailer(Stream.Client stream, HeadersFrame frame)
            {
            }

            /**
             * <p>Callback method invoked when the stream idle timeout elapses.</p>
             *
             * @param stream  the stream
             * @param failure the timeout failure
             * @param promise the promise to complete with true to reset the stream,
             *                false to ignore the idle timeout
             */
            default void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
            {
                promise.succeeded(true);
            }

            /**
             * <p>Callback method invoked when a stream failure occurred.</p>
             * <p>Typical stream failures, among others, are failures to
             * decode a HEADERS frame, or failures to read bytes because
             * the stream has been reset.</p>
             *
             * @param stream the stream
             * @param error the failure error
             * @param failure the cause of the failure
             */
            default void onFailure(Stream.Client stream, long error, Throwable failure)
            {
            }
        }
    }

    /**
     * <p>The server side version of {@link Stream}.</p>
     */
    interface Server extends Stream
    {
        /**
         * <p>Responds to a request performed via {@link Session.Client#newRequest(HeadersFrame, Client.Listener, Promise.Invocable)},
         * sending the given HEADERS frame containing the response status code and response headers.</p>
         *
         * @param frame the HEADERS frame containing the response headers
         * @param promise the {@link Promise.Invocable} that gets notified when the frame has been sent
         */
        void respond(HeadersFrame frame, Promise.Invocable<Stream> promise);

        /**
         * <p>A {@link Stream.Server.Listener} is the passive counterpart of a {@link Stream.Server}
         * and receives server-side events happening on an HTTP/3 stream.</p>
         *
         * @see Stream.Server
         */
        interface Listener
        {
            /**
             * <p>Callback method invoked when a request is received.</p>
             * <p>Applications should implement this method to process HTTP/3 requests,
             * typically providing an HTTP/3 response via {@link #respond(HeadersFrame, Promise.Invocable)}:</p>
             * <pre>{@code
             * class MyStreamServerListener implements Stream.Server.Listener
             * {
             *     @Override
             *     public void onRequest(Stream.Server stream, HeadersFrame frame)
             *     {
             *         // Send a response.
             *         var response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY);
             *         stream.respond(new HeadersFrame(response, true), Promise.Invocable.noop());
             *         if (!frame.isLast())
             *             stream.demand();
             *     }
             * }
             * }</pre>
             * <p>If there is request content (indicated by the fact that the given HEADERS frame
             * is not the last in the stream), then applications should call {@link Stream#demand()}
             * to signal interest in receiving request content, and override either
             * {@link #onDataAvailable(Server)} or {@link #onDataAvailable(Server, boolean)}
             * to read and consume request content.</p>
             *
             * @param stream the stream
             * @param frame the HEADERS frame containing the request headers
             */
            default void onRequest(Stream.Server stream, HeadersFrame frame)
            {
                if (!frame.isLast())
                    stream.demand();
            }

            /**
             * <p>A simplified version of {@link #onDataAvailable(Stream.Server, boolean)}.</p>
             * <p>The default implementation of this method reads and discards data.</p>
             *
             * @param stream the stream
             * @see Stream#demand()
             */
            default void onDataAvailable(Stream.Server stream)
            {
                try
                {
                    while (true)
                    {
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        chunk.release();
                        if (chunk.isLast())
                            return;
                    }
                }
                catch (Throwable x)
                {
                    onFailure(stream, HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
                }
            }

            /**
             * <p>Callback method invoked if the application has expressed
             * {@link Stream#demand() demand} for content, and if there may
             * be content available.</p>
             * <p>A server application that wishes to handle request content
             * should typically call {@link Stream#demand()} from
             * {@link #onRequest(Server, HeadersFrame)}.</p>
             * <p>A client application that wishes to handle response content
             * should typically call {@link Stream#demand()} from
             * {@link Stream.Client.Listener#onResponse(Client, HeadersFrame)}.</p>
             * <p>Just prior calling this method, the outstanding demand is
             * cancelled; applications that implement this method should read
             * content calling {@link Stream#read()}, and call
             * {@link Stream#demand()} to signal to the implementation to call
             * again this method when there may be more content available.</p>
             * <p>Only one thread at a time invokes this method, although it
             * may not be the same thread across different invocations.</p>
             * <p>It is always guaranteed that invoking {@link Stream#demand()}
             * from within this method will not cause a {@link StackOverflowError}.</p>
             * <p>Typical usage:</p>
             * <pre>{@code
             * class MyStreamListener implements Stream.Server.Listener
             * {
             *     @Override
             *     public void onDataAvailable(Stream.Server stream, boolean immediate)
             *     {
             *         // Read a chunk of the content.
             *         Content.Chunk chunk = stream.read();
             *         if (chunk == null)
             *         {
             *             // No data available now, demand to be called back.
             *             stream.demand();
             *         }
             *         else
             *         {
             *             // Process the content chunk.
             *             process(chunk);
             *             // Notify that the content has been consumed.
             *             chunk.release();
             *             if (!chunk.isLast())
             *             {
             *                 // Demand to be called back.
             *                 stream.demand();
             *             }
             *         }
             *     }
             * }
             * }</pre>
             * <p>The default implementation of this method calls
             * {@link #onDataAvailable(Stream.Server)}.</p>
             *
             * @param stream the stream
             * @param immediate {@code true} when data is immediately available at the time
             * {@link #demand()} is invoked (this method is directly invoked from {@link #demand()};
             * {@code false} when data was not immediately available at the time {@link #demand()}
             * was called, but is now available (this method is invoked from the network layer,
             * not directly from {@link #demand()}
             * @see Stream#demand()
             */
            default void onDataAvailable(Stream.Server stream, boolean immediate)
            {
                onDataAvailable(stream);
            }

            /**
             * <p>Callback method invoked when a trailer is received.</p>
             *
             * @param stream the stream
             * @param frame the HEADERS frame containing the trailer headers
             */
            default void onTrailer(Stream.Server stream, HeadersFrame frame)
            {
            }

            /**
             * <p>Callback method invoked when the stream idle timeout elapses.</p>
             *
             * @param stream  the stream
             * @param failure the timeout failure
             * @param promise the promise to complete with true to reset the stream,
             *                false to ignore the idle timeout
             */
            default void onIdleTimeout(Stream.Server stream, TimeoutException failure, Promise<Boolean> promise)
            {
                promise.succeeded(true);
            }

            /**
             * <p>Callback method invoked when a stream failure occurred.</p>
             * <p>Typical stream failures, among others, are failures to
             * decode a HEADERS frame, or failures to read bytes because
             * the stream has been reset.</p>
             *
             * @param stream the stream
             * @param error the failure error
             * @param failure the cause of the failure
             */
            default void onFailure(Stream.Server stream, long error, Throwable failure)
            {
            }
        }
    }
}
