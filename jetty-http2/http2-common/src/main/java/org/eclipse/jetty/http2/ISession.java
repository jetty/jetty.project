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

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>The SPI interface for implementing an HTTP/2 session.</p>
 * <p>This class extends {@link Session} by adding the methods required to
 * implement the HTTP/2 session functionalities.</p>
 */
public interface ISession extends Session
{
    @Override
    public IStream getStream(int streamId);

    /**
     * <p>Removes the given {@code stream}.</p>
     *
     * @param stream the stream to remove
     * @return whether the stream was removed
     */
    public boolean removeStream(IStream stream);

    /**
     * <p>Sends the given list of frames to create a new {@link Stream}.</p>
     *
     * @param frames the list of frames to send
     * @param promise the promise that gets notified of the stream creation
     * @param listener the listener that gets notified of stream events
     */
    public void newStream(IStream.FrameList frames, Promise<Stream> promise, Stream.Listener listener);

    /**
     * <p>Enqueues the given frames to be written to the connection.</p>
     * @param stream the stream the frames belong to
     * @param frames the frames to enqueue
     * @param callback the callback that gets notified when the frames have been sent
     */
    public void frames(IStream stream, List<? extends Frame> frames, Callback callback);

    /**
     * <p>Enqueues the given PUSH_PROMISE frame to be written to the connection.</p>
     * <p>Differently from {@link #frames(IStream, List, Callback)}, this method
     * generates atomically the stream id for the pushed stream.</p>
     *
     * @param stream the stream associated to the pushed stream
     * @param promise the promise that gets notified of the pushed stream creation
     * @param frame the PUSH_PROMISE frame to enqueue
     * @param listener the listener that gets notified of pushed stream events
     */
    public void push(IStream stream, Promise<Stream> promise, PushPromiseFrame frame, Stream.Listener listener);

    /**
     * <p>Enqueues the given DATA frame to be written to the connection.</p>
     *
     * @param stream the stream the data frame belongs to
     * @param callback the callback that gets notified when the frame has been sent
     * @param frame the DATA frame to send
     */
    public void data(IStream stream, Callback callback, DataFrame frame);

    /**
     * <p>Updates the session send window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the session send window
     * @return the previous value of the session send window
     */
    public int updateSendWindow(int delta);

    /**
     * <p>Updates the session receive window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the session receive window
     * @return the previous value of the session receive window
     */
    public int updateRecvWindow(int delta);

    /**
     * <p>Callback method invoked when a WINDOW_UPDATE frame has been received.</p>
     *
     * @param stream the stream the window update belongs to, or null if the window update belongs to the session
     * @param frame the WINDOW_UPDATE frame received
     */
    public void onWindowUpdate(IStream stream, WindowUpdateFrame frame);

    /**
     * @return whether the push functionality is enabled
     */
    public boolean isPushEnabled();

    /**
     * <p>Callback invoked when the connection reads -1.</p>
     *
     * @see #onIdleTimeout()
     * @see #close(int, String, Callback)
     */
    public void onShutdown();

    /**
     * <p>Callback invoked when the idle timeout expires.</p>
     *
     * @return {@code true} if the session has expired
     * @see #onShutdown()
     * @see #close(int, String, Callback)
     */
    public boolean onIdleTimeout();

    /**
     * <p>Callback method invoked during an HTTP/1.1 to HTTP/2 upgrade requests
     * to process the given synthetic frame.</p>
     *
     * @param frame the synthetic frame to process
     */
    public void onFrame(Frame frame);

    /**
     * <p>Callback method invoked when bytes are flushed to the network.</p>
     *
     * @param bytes the number of bytes flushed to the network
     * @throws IOException if the flush should fail
     */
    public void onFlushed(long bytes) throws IOException;

    /**
     * @return the number of bytes written by this session
     */
    public long getBytesWritten();

    /**
     * <p>Callback method invoked when a DATA frame is received.</p>
     *
     * @param frame the DATA frame received
     * @param callback the callback to notify when the frame has been processed
     */
    public void onData(DataFrame frame, Callback callback);

    /**
     * <p>Gracefully closes the session, returning a {@code CompletableFuture} that
     * is completed when all the streams currently being processed are completed.</p>
     * <p>Implementation is idempotent, i.e. calling this method a second time
     * or concurrently results in a no-operation.</p>
     *
     * @return a {@code CompletableFuture} that is completed when all the streams are completed
     */
    public CompletableFuture<Void> shutdown();
}
