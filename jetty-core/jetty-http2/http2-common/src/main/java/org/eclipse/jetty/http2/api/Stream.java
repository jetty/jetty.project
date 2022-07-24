//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.api;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data on top of a {@link Session}.</p>
 * <p>Differently from socket streams, where the input and output streams are permanently associated
 * with the socket (and hence with the connection that the socket represents), there can be multiple
 * HTTP/2 streams present concurrently for an HTTP/2 session.</p>
 * <p>A {@link Stream} maps to an HTTP request/response cycle, and after the request/response cycle is
 * completed, the stream is closed and removed from the session.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link Stream.Listener} is the passive part, and
 * its callbacks are invoked when events happen on the stream.</p>
 *
 * @see Stream.Listener
 */
public interface Stream
{
    /**
     * @return the stream unique id
     */
    public int getId();

    /**
     * @return the {@link org.eclipse.jetty.http2.api.Stream.Listener} associated with this stream
     */
    public Listener getListener();

    /**
     * @return the session this stream is associated to
     */
    public Session getSession();

    /**
     * <p>Sends the given HEADERS {@code frame} representing an HTTP response.</p>
     *
     * @param frame the HEADERS frame to send
     * @return the CompletableFuture that gets notified when the frame has been sent
     */
    public default CompletableFuture<Stream> headers(HeadersFrame frame)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        headers(frame, Callback.from(() -> result.succeeded(this), result::failed));
        return result;
    }

    /**
     * <p>Sends the given HEADERS {@code frame}.</p>
     * <p>Typically used to send an HTTP response or to send the HTTP response trailers.</p>
     *
     * @param frame the HEADERS frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void headers(HeadersFrame frame, Callback callback);

    /**
     * <p>Sends the given PUSH_PROMISE {@code frame}.</p>
     *
     * @param frame the PUSH_PROMISE frame to send
     * @param listener the listener that gets notified of stream events
     * @return the CompletableFuture that gets notified of the pushed stream creation
     */
    public default CompletableFuture<Stream> push(PushPromiseFrame frame, Listener listener)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        push(frame, result, listener);
        return result;
    }

    /**
     * <p>Sends the given PUSH_PROMISE {@code frame}.</p>
     *
     * @param frame the PUSH_PROMISE frame to send
     * @param promise the promise that gets notified of the pushed stream creation
     * @param listener the listener that gets notified of stream events
     */
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener);

    /**
     * <p>Reads DATA frames from this stream, wrapping them in retainable {@link Data}
     * objects.</p>
     * <p>The returned {@link Stream.Data} object may be {@code null}, indicating
     * that the end of the read side of the stream has not yet been reached, which
     * may happen in these cases:</p>
     * <ul>
     *   <li>not all the bytes have been received so far, for example the remote
     *   peer did not send them yet, or they are in-flight</li>
     *   <li>all the bytes have been received, but there is a trailer HEADERS
     *   frame to be received to indicate the end of the read side of the
     *   stream</li>
     * </ul>
     * <p>When the returned {@link Stream.Data} object is not {@code null},
     * applications <em>must</em> call, either immediately or later (possibly
     * asynchronously) {@link Stream.Data#release()} to notify the
     * implementation that the bytes have been processed.</p>
     * <p>{@link Stream.Data} objects may be stored away for later, asynchronous,
     * processing (for example, to process them only when all of them have been
     * received).</p>
     *
     * @return a {@link Stream.Data} object containing the DATA frame,
     * or null if no DATA frame is available
     * @see #demand()
     * @see Listener#onDataAvailable(Stream)
     */
    public Data readData();

    /**
     * <p>Sends the given DATA {@code frame}.</p>
     *
     * @param frame the DATA frame to send
     * @return the CompletableFuture that gets notified when the frame has been sent
     */
    public default CompletableFuture<Stream> data(DataFrame frame)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        data(frame, Callback.from(() -> result.succeeded(this), result::failed));
        return result;
    }

    /**
     * <p>Sends the given DATA {@code frame}.</p>
     *
     * @param frame the DATA frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void data(DataFrame frame, Callback callback);

    /**
     * <p>Sends the given RST_STREAM {@code frame}.</p>
     *
     * @param frame the RST_FRAME to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void reset(ResetFrame frame, Callback callback);

    /**
     * @param key the attribute key
     * @return an arbitrary object associated with the given key to this stream
     * or null if no object can be found for the given key.
     * @see #setAttribute(String, Object)
     */
    public Object getAttribute(String key);

    /**
     * @param key the attribute key
     * @param value an arbitrary object to associate with the given key to this stream
     * @see #getAttribute(String)
     * @see #removeAttribute(String)
     */
    public void setAttribute(String key, Object value);

    /**
     * @param key the attribute key
     * @return the arbitrary object associated with the given key to this stream
     * @see #setAttribute(String, Object)
     */
    public Object removeAttribute(String key);

    /**
     * @return whether this stream is local or remote
     */
    public boolean isLocal();

    /**
     * @return whether this stream has been reset
     */
    public boolean isReset();

    /**
     * @return whether the stream is closed remotely.
     * @see #isClosed()
     */
    boolean isRemotelyClosed();

    /**
     * @return whether this stream is closed, both locally and remotely.
     */
    public boolean isClosed();

    /**
     * @return the stream idle timeout
     * @see #setIdleTimeout(long)
     */
    public long getIdleTimeout();

    /**
     * @param idleTimeout the stream idle timeout
     * @see #getIdleTimeout()
     * @see Stream.Listener#onIdleTimeout(Stream, Throwable)
     */
    public void setIdleTimeout(long idleTimeout);

    /**
     * <p>Demands more {@code DATA} frames for this stream, causing
     * {@link Listener#onDataAvailable(Stream)} to be invoked, possibly at a later time,
     * when the stream has data to be read.</p>
     * <p>This method is idempotent: calling it when there already is an
     * outstanding demand to invoke {@link Listener#onDataAvailable(Stream)}
     * is a no-operation.</p>
     * <p>The thread invoking this method may invoke directly
     * {@link Listener#onDataAvailable(Stream)}, unless another thread
     * that must invoke {@link Listener#onDataAvailable(Stream)}
     * notices the outstanding demand first.</p>
     * <p>When all bytes have been read (via {@link #readData()}), further
     * invocations of this method are a no-operation.</p>
     * <p>It is always guaranteed that invoking this method from within
     * {@code onDataAvailable(Stream)} will not cause a
     * {@link StackOverflowError}.</p>
     *
     * @see #readData()
     * @see Listener#onDataAvailable(Stream)
     */
    public void demand();

    /**
     * <p>A {@link Stream.Listener} is the passive counterpart of a {@link Stream} and receives
     * events happening on an HTTP/2 stream.</p>
     * <p>HTTP/2 data is flow controlled - this means that only a finite number of data events
     * are delivered, until the flow control window is exhausted.</p>
     * <p>Applications control the delivery of data events by requesting them via
     * {@link Stream#demand()}; the first event is always delivered, while subsequent
     * events must be explicitly demanded.</p>
     * <p>Applications control the HTTP/2 flow control by completing the callback associated
     * with data events - this allows the implementation to recycle the data buffer and
     * eventually to enlarge the flow control window so that the sender can send more data.</p>
     *
     * @see Stream
     */
    public interface Listener
    {
        /**
         * <p>Callback method invoked when a stream is created locally by
         * {@link Session#newStream(HeadersFrame, Promise, Listener)}.</p>
         *
         * @param stream the newly created stream
         */
        public default void onNewStream(Stream stream)
        {
        }

        /**
         * <p>Callback method invoked when a HEADERS frame representing the HTTP response has been received.</p>
         *
         * @param stream the stream
         * @param frame the HEADERS frame received
         */
        public default void onHeaders(Stream stream, HeadersFrame frame)
        {
            stream.demand();
        }

        /**
         * <p>Callback method invoked when a PUSH_PROMISE frame has been received.</p>
         *
         * @param stream the pushed stream
         * @param frame the PUSH_PROMISE frame received
         * @return a Stream.Listener that will be notified of pushed stream events
         */
        public default Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            return null;
        }

        /**
         * <p>Callback method invoked if the application has expressed
         * {@link Stream#demand() demand} for DATA frames, and if there
         * may be content available.</p>
         * <p>Applications that wish to handle DATA frames should call
         * {@link Stream#demand()} for this method to be invoked when
         * the data is available.</p>
         * <p>Server applications should typically demand from {@link #onNewStream(Stream)}
         * (upon receiving an HTTP request), while client applications
         * should typically demand from {@link #onHeaders(Stream, HeadersFrame)}
         * (upon receiving an HTTP response).</p>
         * <p>Just prior calling this method, the outstanding demand is
         * cancelled; applications that implement this method should read
         * content calling {@link Stream#readData()}, and call
         * {@link Stream#demand()} to signal to the implementation to call
         * again this method when there may be more content available.</p>
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
         *             data.release();
         *             if (!data.frame().isEndStream())
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
         * @see Stream#demand()
         */
        public default void onDataAvailable(Stream stream)
        {
        }

        /**
         * <p>Callback method invoked when a RST_STREAM frame has been received for this stream.</p>
         *
         * @param stream the stream
         * @param frame the RST_FRAME received
         * @param callback the callback to complete when the reset has been handled
         */
        public default void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            callback.succeeded();
        }

        /**
         * <p>Callback method invoked when the stream exceeds its idle timeout.</p>
         *
         * @param stream the stream
         * @param x the timeout failure
         * @return true to reset the stream, false to ignore the idle timeout
         * @see #getIdleTimeout()
         */
        public default boolean onIdleTimeout(Stream stream, Throwable x)
        {
            return true;
        }

        /**
         * <p>Callback method invoked when the stream failed.</p>
         *
         * @param stream the stream
         * @param error the error code
         * @param reason the error reason, or null
         * @param failure the failure
         * @param callback the callback to complete when the failure has been handled
         */
        public default void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
        {
            callback.succeeded();
        }

        /**
         * <p>Callback method invoked after the stream has been closed.</p>
         *
         * @param stream the stream
         */
        public default void onClosed(Stream stream)
        {
        }
    }

    /**
     * <p>A {@link Retainable} wrapper of a {@link DataFrame}.</p>
     */
    public abstract static class Data implements Retainable
    {
        public static Data eof(int streamId)
        {
            return new Data.EOF(streamId);
        }

        private final DataFrame frame;

        public Data(DataFrame frame)
        {
            this.frame = frame;
        }

        public DataFrame frame()
        {
            return frame;
        }

        @Override
        public void retain()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean release()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), frame());
        }

        private static class EOF extends Data
        {
            public EOF(int streamId)
            {
                super(new DataFrame(streamId, BufferUtil.EMPTY_BUFFER, true));
            }
        }
    }
}
