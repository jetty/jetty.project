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

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.util.Promise;

/// A stream represents a unidirectional or bidirectional exchange
/// of data within a [Session].
///
/// Streams are multiplexed within a session: there can be multiple,
///  different, concurrent streams present in a session.
///
/// `Stream` is the active part and by calling its API applications
/// can generate events on the stream.
/// Conversely, [Stream.Listener] is the passive part, and its
/// methods are invoked when events happen on the stream.
///
/// @see Stream.Listener
public interface Stream
{
    /// @return the stream id
    long getId();

    /// @return whether the stream is bidirectional
    boolean isBidirectional();

    /// @return whether the stream is local or remote
    boolean isLocal();

    /// Returns whether the stream is fully closed, both
    /// [locally][#isLocallyClosed()] and [remotely][#isRemotelyClosed()].
    ///
    /// @return whether the stream is fully closed
    boolean isClosed();

    /// Returns whether the stream is locally closed.
    ///
    /// A locally closed stream will not send further data.
    ///
    /// A stream becomes locally closed when either it has sent
    /// [the last data][#data(boolean, List, Promise.Invocable)],
    /// or sent a [reset frame][#reset(long, Promise.Invocable)].
    ///
    /// @return whether the stream is locally closed
    /// @see #isRemotelyClosed()
    /// @see #isClosed()
    boolean isLocallyClosed();

    /// Returns whether the stream is remotely closed.
    ///
    /// A remotely closed stream will not receive further data.
    ///
    /// A stream becomes remotely closed when it has [#read()]
    /// the last data, or it has received a reset frame.
    ///
    /// @return whether the stream is remotely closed
    /// @see #isLocallyClosed()
    /// @see #isClosed()
    boolean isRemotelyClosed();

    /// @return the idle timeout in milliseconds
    long getIdleTimeout();

    /// @param idleTimeout the idle timeout in milliseconds
    void setIdleTimeout(long idleTimeout);

    /// @return the [Session] this stream is associated to
    Session getSession();

    /// Reads data from this stream, if any.
    ///
    /// The returned [Content.Chunk] object may be `null` indicating
    /// that the end of the read side of the stream has not yet been reached.
    ///
    /// A `null` chunk may be returned when not all the bytes have been
    /// received so far, for example when the remote peer did not send
    /// them yet, or they are still in-flight.
    ///
    /// When the returned [Content.Chunk] object is not `null`,
    /// the flow control window has been enlarged by the data length.
    ///
    /// Applications _must_ call, either immediately or later (even
    /// asynchronously from a different thread) [Content.Chunk#release()]
    /// to notify the implementation that the bytes have been processed.
    ///
    /// [Content.Chunk] objects may be stored away for later,
    /// asynchronous, processing (for example, to process them only when
    /// all of them have been received).
    ///
    /// Once the returned [Content.Chunk] object indicates that the end
    /// of the read side of the stream has been reached, further calls to this
    /// method will return a [Content.Chunk] object with the same indication,
    /// although the instance may be different.
    ///
    /// @return a [Content.Chunk] object containing the data bytes
    /// or a failure, or `null` if no data bytes are available
    /// @see #demand()
    Content.Chunk read();

    /// Demands more data bytes for this stream.
    ///
    /// Calling this method causes [Listener#onDataAvailable(Stream, boolean)]
    /// to be invoked, possibly at a later time, when the stream has data
    /// to be read, but also when the stream has reached EOF.
    ///
    /// This method is idempotent: calling it when there already is an
    /// outstanding demand to invoke [Listener#onDataAvailable(Stream, boolean)]
    /// is a no-operation.
    ///
    /// The thread invoking this method may invoke directly
    /// [Listener#onDataAvailable(Stream, boolean)], unless another thread
    /// that must invoke [Listener#onDataAvailable(Stream, boolean)]
    /// notices the outstanding demand first.
    ///
    /// It is always guaranteed that invoking this method from within
    /// [Listener#onDataAvailable(Stream, boolean)] will not cause a
    /// [StackOverflowError].
    ///
    /// @see #read()
    /// @see Listener#onDataAvailable(Stream, boolean)
    void demand();

    /// Sends a `STREAM` frame with the given data bytes and the indication
    /// of whether they are the last to be sent.
    ///
    /// @param last whether the data bytes are the last
    /// @param data the list of data bytes to send
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// data has been sent
    void data(boolean last, RetainableByteBuffer data, Promise.Invocable<Stream> promise);

    /// Sends a `MAX_STREAM_DATA` frame with the new total max data bytes
    /// that this peer is willing to receive.
    ///
    /// @param maxData the max data bytes this peer is willing to receive
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// frame has been sent
    void maxData(long maxData, Promise.Invocable<Stream> promise);

    /// Sends a `RESET_STREAM` frame, with the given application error
    /// code.
    ///
    /// After sending a `RESET_STREAM` frame, no more data can be sent
    /// and the stream is locally closed.
    ///
    /// @param appErrorCode the application error code
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// frame has been sent
    void reset(long appErrorCode, Promise.Invocable<Stream> promise);

    /// Sends a `STOP_SENDING` frame, with the given application error
    /// code.
    ///
    /// @param appErrorCode the application error code
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// frame has been sent
    void stopSending(long appErrorCode, Promise.Invocable<Stream> promise);

    /// Sends a `STREAM_DATA_BLOCKED` frame, with the given data offset.
    ///
    /// @param offset the data offset
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// frame has been sent
    void dataBlocked(long offset, Promise.Invocable<Stream> promise);

    /// Abruptly terminates this stream with the given error.
    ///
    /// This method removes this stream from its session and
    /// then terminates the QUIC stream, via [#stopSending(long, Promise.Invocable)],
    /// and then a [#reset(long, Promise.Invocable)].
    ///
    /// @param appErrorCode the application error code
    /// @param failure the failure that caused the disconnect of the stream
    /// @param promise the [Promise.Invocable] that gets notified when the
    /// disconnect is completed
    void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<Stream> promise);

    /// A [Stream.Listener] is the passive counterpart of a [Stream]
    /// and receives events, triggered by the remote peer, happening on the stream.
    ///
    /// Stream data is requested using [Stream#demand()], and when data
    /// is available [#onDataAvailable(Stream, boolean)] is invoked.
    ///
    /// @see Stream
    interface Listener extends EventListener
    {
        /// Callback method invoked when receiving a frame that causes
        /// the creation of a new stream.
        ///
        /// The default implementation of this method calls [Stream#demand()].
        ///
        /// @param stream the newly created stream
        /// @param frame the frame that caused the creation of the stream
        default void onNewStream(Stream stream, Frame.WithStreamId frame)
        {
            stream.demand();
        }

        /// A simplified version of [#onDataAvailable(Stream, boolean)].
        ///
        /// The default implementation of this method reads and discards data.
        ///
        /// @param stream the stream
        /// @see Stream#demand()
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

        /// Callback method invoked when the application has expressed
        /// [demand][Stream#demand()] for data carried by STREAM frames,
        /// and there are STREAM frames available.
        ///
        /// Server applications should typically demand from
        /// [Stream.Listener#onNewStream(Stream, Frame.WithStreamId)]
        /// (upon receiving the first stream frame), while client applications
        /// should typically demand after obtaining a [Stream] via
        /// [Session#newStream(long, Listener)].
        ///
        /// Just prior calling this method, the outstanding demand is
        /// cancelled; applications that implement this method should read
        /// content calling [Stream#read()], and then call
        /// [Stream#demand()] to signal to the implementation to call
        /// again this method when there may be more data available.
        ///
        /// Only one thread at a time invokes this method, although it
        /// may not be the same thread across different invocations.
        ///
        /// It is always guaranteed that invoking [Stream#demand()]
        /// from within this method will not cause a [StackOverflowError].
        ///
        /// Typical usage:
        /// ```java
        /// class MyStreamListener implements Stream.Listener
        /// {
        ///     @Override
        ///     public void onDataAvailable(Stream stream, boolean immediate)
        ///     {
        ///         while (true)
        ///         {
        ///             // Read a chunk of the content.
        ///             Content.Chunk chunk = stream.read();
        ///
        ///             if (chunk == null)
        ///             {
        ///                 // No data available now, demand to be called back.
        ///                 stream.demand();
        ///                 return;
        ///             }
        ///
        ///             // Process the content chunk.
        ///             process(chunk);
        ///
        ///             // Notify that the content has been consumed.
        ///             chunk.release();
        ///
        ///             if (chunk.isLast())
        ///             {
        ///                 // All data has been processed.
        ///                 return;
        ///             }
        ///         }
        ///     }
        /// }
        /// ```
        ///
        /// The default implementation of this method calls
        /// [#onDataAvailable(Stream)].
        ///
        /// @param stream the stream
        /// @param immediate `true` when data is immediately available at the time
        /// [#demand()] is invoked (this method is directly invoked from [#demand()];
        /// `false` when data was not immediately available at the time [#demand()]
        /// was called, but is now available (this method is invoked from the network layer,
        /// not directly from [#demand()]
        /// @see Stream#demand()
        default void onDataAvailable(Stream stream, boolean immediate)
        {
            onDataAvailable(stream);
        }

        /// Invoked when a `STREAM_DATA_BLOCKED` frame has been received.
        ///
        /// This event is only emitted for informational purposes.
        ///
        /// @param stream the stream
        /// @param frame the frame
        default void onDataBlocked(Stream stream, StreamDataBlockedFrame frame)
        {
        }

        /// Invoked when a `MAX_STREAM_DATA` frame has been received.
        ///
        /// This event is only emitted for informational purposes.
        ///
        /// @param stream the stream
        /// @param frame the frame
        default void onMaxData(Stream stream, StreamMaxDataFrame frame)
        {
        }

        /// Invoked when a `STOP_SENDING` frame has been received.
        ///
        /// This event is only emitted for informational purposes.
        ///
        /// @param stream the stream
        /// @param frame the frame
        default void onStopSending(Stream stream, StopSendingFrame frame)
        {
        }

        /// Invoked when a `RESET_STREAM` frame has been received.
        ///
        /// This event is only emitted for informational purposes.
        ///
        /// @param stream the stream
        /// @param frame the frame
        default void onReset(Stream stream, ResetFrame frame)
        {
        }

        /// Invoked when the stream has been [closed][Stream#isClosed()].
        ///
        /// A stream is closed when either:
        /// * The receiving side read the last frame in the stream,
        ///   and the sending side sent the last frame in the stream
        /// * The stream is [disconnected][#disconnect(long, Throwable, Promise.Invocable)],
        ///   for example due to failures.
        ///
        /// @param stream the stream
        default void onClose(Stream stream)
        {
        }

        /// Invoked when the stream is idle for longer than the idle timeout.
        ///
        /// @param stream the stream
        /// @param failure the idle timeout failure
        /// @param promise the promise to complete to notify the other peer that this stream is closing
        default void onIdleTimeout(Stream stream, TimeoutException failure, Promise.Invocable<Boolean> promise)
        {
            promise.succeeded(true);
        }

        /// Invoked when a stream failure is detected.
        ///
        /// @param stream the stream
        /// @param failure the stream failure
        default void onFailure(Stream stream, Throwable failure)
        {
        }
    }
}
