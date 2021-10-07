//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data within a {@link Session}.</p>
 * <p>A {@link Stream} maps to an HTTP/3 request/response cycle, and after the request/response
 * cycle is completed, the stream is closed and removed from the {@link Session}.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link Stream.Listener} is the passive part, and
 * its callbacks are invoked when events happen on the stream.</p>
 * <p>The client initiates a stream by sending a HEADERS frame containing the HTTP/3 request URI
 * and request headers, and zero or more DATA frames containing request content.</p>
 * <p>Similarly, the server responds by sending a HEADERS frame containing the HTTP/3 response
 * status code and response headers, and zero or more DATA frames containing response content.</p>
 * <p>Both client and server can end their side of the stream by sending a final frame with
 * the {@code last} flag set to {@code true}, see {@link HeadersFrame#HeadersFrame(MetaData, boolean)}
 * and {@link DataFrame#DataFrame(ByteBuffer, boolean)}.</p>
 *
 * @see Stream.Listener
 */
public interface Stream
{
    /**
     * @return the stream id
     */
    public long getId();

    /**
     * @return the session this stream is associated to
     */
    public Session getSession();

    /**
     * <p>Responds to a request performed via {@link Session.Client#newRequest(HeadersFrame, Listener)},
     * sending the given HEADERS frame containing the response status code and response headers.</p>
     *
     * @param frame the HEADERS frame containing the response headers
     * @return the {@link CompletableFuture} that gets notified when the frame has been sent
     */
    public CompletableFuture<Stream> respond(HeadersFrame frame);

    /**
     * <p>Sends the given DATA frame containing some or all the bytes
     * of the request content or of the response content.</p>
     *
     * @param frame the DATA frame containing some or all the bytes of the request or of the response.
     * @return the {@link CompletableFuture} that gets notified when the frame has been sent
     */
    public CompletableFuture<Stream> data(DataFrame frame);

    /**
     * <p>Reads request content bytes or response content bytes.</p>
     * <p>The returned {@link Stream.Data} object may be {@code null}, indicating
     * that the end of the read side of the stream has not yet been reached, which
     * may happen in these cases:</p>
     * <ul>
     *   <li>not all the bytes have been received so far, and a further attempt
     *   to call this method returns {@code null} because the rest of the bytes
     *   are not yet available (for example, the remote peer did not send them
     *   yet, or they are in-flight)</li>
     *   <li>all the bytes have been received, but there is a trailer HEADERS
     *   frame to be received to indicate the end of the read side of the
     *   stream.</li>
     * </ul>
     * <p>When the returned {@link Stream.Data} object is not {@code null},
     * applications <em>must</em> call {@link Stream.Data#complete()} to
     * notify the implementation that the bytes have been processed.</p>
     * <p>{@link Stream.Data} objects may be stored away for later, asynchronous,
     * processing (for example, to process them only when all of them have been
     * received).</p>
     * <p>This method <em>must only</em> be called when there is no outstanding
     * {@link #demand() demand}.</p>
     * <p>Practically, this means that this method should be called either
     * synchronously from within {@link Stream.Listener#onDataAvailable(Stream)},
     * or applications must arrange, for example using a
     * {@link java.util.concurrent.Semaphore}, that a call to
     * {@link Stream.Listener#onDataAvailable(Stream)} is made before
     * calling this method (possibly from a different thread).</p>
     *
     * @return a {@link Stream.Data} object containing the request bytes or
     * the response bytes, or null if no bytes are available
     * @see Stream.Listener#onDataAvailable(Stream)
     */
    public Stream.Data readData();

    /**
     * <p>Causes {@link Stream.Listener#onDataAvailable(Stream)} to be invoked,
     * possibly at a later time, when the stream has data to be read.</p>
     * <p>This method is idempotent: calling it when there already is an
     * outstanding demand to invoke {@link Stream.Listener#onDataAvailable(Stream)}
     * is a no-operation.</p>
     * <p>The thread invoking this method may invoke directly
     * {@link Stream.Listener#onDataAvailable(Stream)}, unless another thread
     * that must invoke {@link Stream.Listener#onDataAvailable(Stream)}
     * notices the outstanding demand first.</p>
     * <p>When all bytes have been read (via {@link #readData()}), further
     * invocations of this method are a no-operation.</p>
     * <p>It is always guaranteed that invoking this method from within
     * {@link Stream.Listener#onDataAvailable(Stream)} will not cause a
     * {@link StackOverflowError}.</p>
     *
     * @see #readData()
     * @see Stream.Listener#onDataAvailable(Stream)
     */
    public void demand();

    /**
     * <p> Sends the given HEADERS frame containing the trailer headers.</p>
     *
     * @param frame the HEADERS frame containing the trailer headers
     * @return the {@link CompletableFuture} that gets notified when the frame has been sent
     */
    public CompletableFuture<Stream> trailer(HeadersFrame frame);

    /**
     * <p>A {@link Stream.Listener} is the passive counterpart of a {@link Stream} and receives
     * events happening on an HTTP/3 stream.</p>
     *
     * @see Stream
     */
    public interface Listener
    {
        /**
         * <p>Callback method invoked when a response is received.</p>
         * <p>To read response content, applications should call
         * {@link Stream#demand()} and override
         * {@link Stream.Listener#onDataAvailable(Stream)}.</p>
         *
         * @param stream the stream
         * @param frame the HEADERS frame containing the response headers
         * @see Stream.Listener#onDataAvailable(Stream)
         */
        public default void onResponse(Stream stream, HeadersFrame frame)
        {
        }

        /**
         * <p>Callback method invoked if the application has expressed
         * {@link Stream#demand() demand} for content, and if there is
         * content available.</p>
         * <p>A server application that wishes to handle request content
         * should typically call {@link Stream#demand()} from
         * {@link Session.Server.Listener#onRequest(Stream, HeadersFrame)}.</p>
         * <p>A client application that wishes to handle response content
         * should typically call {@link Stream#demand()} from
         * {@link #onResponse(Stream, HeadersFrame)}.</p>
         * <p>Just prior calling this method, the outstanding demand is
         * cancelled; applications that implement this method should read
         * content calling {@link Stream#readData()}, and call
         * {@link Stream#demand()} to signal to the implementation to call
         * again this method when there is more content available.</p>
         * <p>Only one thread at a time invokes this method, although it
         * may not be the same thread across different invocations.</p>
         * <p>It is always guaranteed that invoking {@link Stream#demand()}
         * from within this method will not cause a {@link StackOverflowError}.</p>
         * <p>Typical usage:</p>
         * <pre>
         * class MyStreamListener implements Stream.Listener
         * {
         *     &#64;Override
         *     public void onDataAvailable(Stream stream)
         *     {
         *         // Read a chunk of the content.
         *         Stream.Data data = stream.readData();
         *         if (data == null)
         *         {
         *             // No data available now, demand to be called back.
         *             stream.demand();
         *         }
         *         else
         *         {
         *             // Process the content.
         *             process(data.getByteBuffer());
         *             // Notify that the content has been consumed.
         *             data.complete();
         *             if (!data.isLast())
         *             {
         *                 // Demand to be called back.
         *                 stream.demand();
         *             }
         *         }
         *     }
         * }
         * </pre>
         *
         * @param stream the stream
         */
        public default void onDataAvailable(Stream stream)
        {
        }

        /**
         * <p>Callback method invoked when a trailer is received.</p>
         *
         * @param stream the stream
         * @param frame the HEADERS frame containing the trailer headers
         */
        public default void onTrailer(Stream stream, HeadersFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the stream idle timeout elapses.</p>
         *
         * @param stream the stream
         * @param failure the timeout failure
         * @return true to reset the stream, false to ignore the idle timeout
         */
        public default boolean onIdleTimeout(Stream stream, Throwable failure)
        {
            return true;
        }

        /**
         * <p>Callback method invoked when a stream failure occurred.</p>
         * <p>Typical stream failures, among others, are failures to
         * decode a HEADERS frame, or failures to read bytes because
         * the stream has been reset.</p>
         *
         * @param stream the stream
         * @param error the error code
         * @param failure the cause of the failure
         */
        public default void onFailure(Stream stream, long error, Throwable failure)
        {
        }
    }

    /**
     * <p>A {@link Stream.Data} instance associates a {@link ByteBuffer}
     * containing request bytes or response bytes with a completion event
     * that applications <em>must</em> trigger when the bytes have been
     * processed.</p>
     *
     * @see Stream#readData()
     */
    public static class Data
    {
        private final DataFrame frame;
        private final Runnable complete;

        public Data(DataFrame frame, Runnable complete)
        {
            this.frame = frame;
            this.complete = complete;
        }

        /**
         * @return the {@link ByteBuffer} containing the data bytes
         *
         * @see #complete()
         */
        public ByteBuffer getByteBuffer()
        {
            return frame.getByteBuffer();
        }

        /**
         * @return whether this is the instance that ends
         * the stream of bytes received from the remote peer
         */
        public boolean isLast()
        {
            return frame.isLast();
        }

        /**
         * <p>The method that applications <em>must</em> invoke to
         * signal that the data bytes have been processed.</p>
         *
         * @see #getByteBuffer()
         */
        public void complete()
        {
            complete.run();
        }

        @Override
        public String toString()
        {
            return String.format("%s[%s]", getClass().getSimpleName(), frame);
        }
    }
}
