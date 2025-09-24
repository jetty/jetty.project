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

package org.eclipse.jetty.quic.api;

import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A stream represents a unidirectional or bidirectional exchange
 * of data within a {@link Session}.</p>
 * <p>Streams are multiplexed within a session, that is
 * there can be multiple, different, concurrent streams present in
 * a session.</p>
 * <p>Stream is the active part and by calling its API applications
 * can generate events on the stream.
 * Conversely, {@link Stream.Listener} is the passive part, and its
 * methods are invoked when events happen on the stream.</p>
 *
 * @see Stream.Listener
 */
public interface Stream
{
    /**
     * <p>Returns the stream id.</p>
     *
     * @return the stream id
     */
    long getId();

    /**
     * <p>Returns whether the stream is bidirectional.</p>
     *
     * @return whether the stream is bidirectional
     */
    boolean isBidirectional();

    /**
     * <p>Returns whether the stream has been created locally or remotely.</p>
     *
     * @return whether the stream is local or remote
     */
    boolean isLocal();

    /**
     * <p>Returns whether the stream is fully closed,
     * both locally and remotely.</p>
     *
     * @return whether the stream is fully closed
     * @see #isLocallyClosed()
     * @see #isRemotelyClosed()
     */
    boolean isClosed();

    /**
     * <p>Returns whether the stream is locally closed.</p>
     * <p>A locally closed stream will not send further data.</p>
     * <p>A stream becomes locally closed when either it has sent
     * {@link #data(boolean, List, Promise.Invocable) the last data}, or issued
     * a {@link #reset(long, Promise.Invocable)}.</p>
     *
     * @return whether the stream is locally closed
     * @see #isRemotelyClosed()
     * @see #isClosed()
     */
    boolean isLocallyClosed();

    /**
     * <p>Returns whether the stream is remotely closed.</p>
     * <p>A remotely closed stream will not receive further data.</p>
     * <p>A stream becomes remotely closed when it has {@link #read()}
     * the last data.</p>
     *
     * @return whether the stream is remotely closed
     * @see #isLocallyClosed()
     * @see #isClosed()
     */
    boolean isRemotelyClosed();

    long getIdleTimeout();

    void setIdleTimeout(long idleTimeout);

    /**
     * <p>Returns the {@link Session} this stream is associated to.</p>
     *
     * @return the {@link Session} this stream is associated to
     */
    Session getSession();

    /**
     * <p>Reads data from this stream, if any.</p>
     * <p>The returned {@link Content.Chunk} object may be {@code null} indicating
     * that the end of the read side of the stream has not yet been reached,
     * which may happen when not all the bytes have been received so far,
     * for example the remote peer did not send them yet, or they are
     * in-flight.</p>
     * <p>When the returned {@link Content.Chunk} object is not {@code null},
     * the flow control window has been enlarged by the data length.
     * Applications <em>must</em> call, either immediately or later (even
     * asynchronously from a different thread) {@link Content.Chunk#release()}
     * to notify the implementation that the bytes have been processed.</p>
     * <p>{@link Content.Chunk} objects may be stored away for later,
     * asynchronous, processing (for example, to process them only when
     * all of them have been received).</p>
     * <p>Once the returned {@link Content.Chunk} object indicates that the end
     * of the read side of the stream has been reached, further calls to this
     * method will return a {@link Content.Chunk} object with the same indication,
     * although the instance may be different.</p>
     *
     * @return a {@link Content.Chunk} object containing the data bytes
     * or a failure, or {@code null} if no data bytes are available
     * @see #demand()
     */
    Content.Chunk read();

    /**
     * <p>Demands more data bytes for this stream.</p>
     * <p>Calling this method causes {@link Listener#onDataAvailable(Stream, boolean)}
     * to be invoked, possibly at a later time, when the stream has data
     * to be read, but also when the stream has reached EOF.</p>
     * <p>This method is idempotent: calling it when there already is an
     * outstanding demand to invoke {@link Listener#onDataAvailable(Stream, boolean)}
     * is a no-operation.</p>
     * <p>The thread invoking this method may invoke directly
     * {@link Listener#onDataAvailable(Stream, boolean)}, unless another thread
     * that must invoke {@link Listener#onDataAvailable(Stream, boolean)}
     * notices the outstanding demand first.</p>
     * <p>It is always guaranteed that invoking this method from within
     * {@code onDataAvailable(Stream)} will not cause a
     * {@link StackOverflowError}.</p>
     *
     * @see #read()
     * @see Listener#onDataAvailable(Stream, boolean)
     */
    void demand();

    /**
     * <p>Sends a STREAM frame with the given data bytes and the indication
     * of whether they are the last to be sent.</p>
     *
     * @param last whether the data bytes are the last
     * @param data the list of data bytes to send
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * data has been sent
     */
    void data(boolean last, List<ByteBuffer> data, Promise.Invocable<Stream> promise);

    /**
     * <p>Sends a MAX_STREAM_DATA frame with the new total max data bytes
     * that this peer is willing to receive.</p>
     *
     * @param maxData the max data bytes this peer is willing to receive
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void maxData(long maxData, Promise.Invocable<Stream> promise);

    /**
     * <p>Sends a RESET_STREAM frame, with the given application error
     * code.</p>
     *
     * @param appErrorCode the application error code
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void reset(long appErrorCode, Promise.Invocable<Stream> promise);

    /**
     * <p>Sends a STOP_SENDING frame, with the given application error
     * code.</p>
     *
     * @param appErrorCode the application error code
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void stopSending(long appErrorCode, Promise.Invocable<Stream> promise);

    /**
     * <p>Sends a STREAM_DATA_BLOCKED frame, with the given offset.</p>
     *
     * @param offset the data offset
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void dataBlocked(long offset, Promise.Invocable<Stream> promise);

    /**
     * <p>Abruptly terminates this stream with the given error.</p>
     * <p>This method removes this stream from its session and
     * then terminates the QUIC stream, via {@link #stopSending(long, Promise.Invocable)},
     * and then a {@link #reset(long, Promise.Invocable)}.</p>
     *
     * @param appErrorCode the application error code
     * @param failure the failure that caused the disconnect of the stream
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * disconnect is completed
     */
    void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise);

    /**
     * <p>A {@link Stream.Listener} is the passive counterpart of a {@link Stream}
     * and receives events, triggered by the remote peer, happening on stream.</p>
     * <p>Stream data is requested using {@link Stream#demand()} and when data
     * is available {@link #onDataAvailable(Stream, boolean)} is invoked.</p>
     *
     * @see Stream
     */
    interface Listener extends EventListener
    {
        /**
         * <p>Callback method invoked when receiving a frame that causes
         * the creation of a new stream.</p>
         *
         * @param stream the newly created stream
         * @param frame the frame that caused the creation of the stream
         */
        default void onNewStream(Stream stream, Frame.WithStreamId frame)
        {
            stream.demand();
        }

        /**
         * <p>A simplified version of {@link #onDataAvailable(Stream, boolean)}.</p>
         * <p>The default implementation of this method reads and discards data.</p>
         *
         * @param stream the stream
         * @see Stream#demand()
         */
        default void onDataAvailable(Stream stream)
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

        /**
         * <p>Callback method invoked when the application has expressed
         * {@link Stream#demand() demand} for data carried by STREAM frames,
         * and there are STREAM frames available.</p>
         * <p>Server applications should typically demand from
         * {@link Stream.Listener#onNewStream(Stream, Frame.WithStreamId)}
         * (upon receiving the first stream frame), while client applications
         * should typically demand after obtaining a {@link Stream} via
         * {@link Session#newStream(long, Listener)}.</p>
         * <p>Just prior calling this method, the outstanding demand is
         * cancelled; applications that implement this method should read
         * content calling {@link Stream#read()}, and call
         * {@link Stream#demand()} to signal to the implementation to call
         * again this method when there may be more data available.</p>
         * <p>Only one thread at a time invokes this method, although it
         * may not be the same thread across different invocations.</p>
         * <p>It is always guaranteed that invoking {@link Stream#demand()}
         * from within this method will not cause a {@link StackOverflowError}.</p>
         * <p>Typical usage:</p>
         * <pre>{@code
         * class MyStreamListener implements Stream.Listener
         * {
         *     @Override
         *     public void onDataAvailable(Stream stream, boolean immediate)
         *     {
         *         while (true)
         *         {
         *             // Read a chunk of the content.
         *             Content.Chunk chunk = stream.read();
         *             if (chunk == null)
         *             {
         *                 // No data available now, demand to be called back.
         *                 stream.demand();
         *                 return;
         *             }
         *
         *             // Process the content chunk.
         *             process(chunk);
         *
         *             // Notify that the content has been consumed.
         *             chunk.release();
         *
         *             if (chunk.isLast())
         *             {
         *                 // All data has been processed.
         *                 return;
         *             }
         *         }
         *     }
         * }
         * }</pre>
         * <p>The default implementation of this method calls
         * {@link #onDataAvailable(Stream)}.</p>
         *
         * @param stream the stream
         * @param immediate {@code true} when data is immediately available at the time
         * {@link #demand()} is invoked (this method is directly invoked from {@link #demand()};
         * {@code false} when data was not immediately available at the time {@link #demand()}
         * was called, but is now available (this method is invoked from the network layer,
         * not directly from {@link #demand()}
         * @see Stream#demand()
         */
        default void onDataAvailable(Stream stream, boolean immediate)
        {
            onDataAvailable(stream);
        }

        /**
         * <p>Invoked when a STREAM_DATA_BLOCKED frame has been received.</p>
         * <p>This event is only emitted for informational purposes.</p>
         *
         * @param stream the stream
         * @param frame the frame
         */
        default void onDataBlocked(Stream stream, StreamDataBlockedFrame frame)
        {
        }

        /**
         * <p>Invoked when a MAX_STREAM_DATA frame has been received.</p>
         * <p>This event is only emitted for informational purposes.</p>
         *
         * @param stream the stream
         * @param frame the frame
         */
        default void onMaxData(Stream stream, StreamMaxDataFrame frame)
        {
        }

        /**
         * <p>Invoked when a STOP_SENDING frame has been received.</p>
         * <p>This event is only emitted for informational purposes.</p>
         *
         * @param stream the stream
         * @param frame the frame
         */
        default void onStopSending(Stream stream, StopSendingFrame frame)
        {
        }

        /**
         * <p>Invoked when a RESET_STREAM frame has been received.</p>
         * <p>This event is only emitted for informational purposes.</p>
         *
         * @param stream the stream
         * @param frame the frame
         */
        default void onReset(Stream stream, ResetFrame frame)
        {
        }

        /**
         * <p>Invoked when the stream has been {@link Stream#isClosed() closed}.</p>
         * <p>A stream is closed when either:</p>
         * <ul>
         * <li>The receiving side read the last frame in the stream,
         * and the sending side sent the last frame in the stream</li>
         * <li>The stream is {@link #disconnect(long, Throwable, Promise.Invocable) disconnected},
         * for example due to failures.</li>
         * </ul>
         *
         * @param stream the stream
         */
        default void onClose(Stream stream)
        {
        }

        /**
         * <p>Invoked when the stream is idle for longer than the idle timeout.</p>
         *
         * @param stream the stream
         * @param failure the idle timeout failure
         * @param promise the promise to complete to notify the other peer that this stream is closing
         */
        default void onIdleTimeout(Stream stream, TimeoutException failure, Promise.Invocable<Boolean> promise)
        {
            promise.succeeded(true);
        }

        /**
         * <p>Invoked when a stream failure is detected.</p>
         *
         * @param stream the stream
         * @param failure the stream failure
         */
        default void onFailure(Stream stream, Throwable failure)
        {
        }
    }
}
