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

package org.eclipse.jetty.http2;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.StreamFrame;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;

/**
 * <p>The SPI interface for implementing an HTTP/2 stream.</p>
 * <p>This class extends {@link Stream} by adding the methods required to
 * implement the HTTP/2 stream functionalities.</p>
 */
public interface IStream extends Stream, Attachable, Closeable
{
    /**
     * @return whether this stream is local or remote
     */
    public boolean isLocal();

    @Override
    public ISession getSession();

    /**
     * @return the {@link org.eclipse.jetty.http2.api.Stream.Listener} associated with this stream
     * @see #setListener(Stream.Listener)
     */
    public Listener getListener();

    public Data readData();

    /**
     * @param listener the {@link org.eclipse.jetty.http2.api.Stream.Listener} associated with this stream
     * @see #getListener()
     */
    public void setListener(Listener listener);

    /**
     * <p>Sends the given list of frames.</p>
     * <p>Typically used to send HTTP headers along with content and possibly trailers.</p>
     *
     * @param frameList the list of frames to send
     * @param callback the callback that gets notified when the frames have been sent
     */
    void send(FrameList frameList, Callback callback);

    /**
     * <p>Processes the given {@code frame}, belonging to this stream.</p>
     *
     * @param frame the frame to process
     * @param callback the callback to complete when frame has been processed
     */
    public void process(Frame frame, Callback callback);

    /**
     * <p>Updates the close state of this stream.</p>
     *
     * @param update whether to update the close state
     * @param event the event that caused the close state update
     * @return whether the stream has been fully closed by this invocation
     */
    public boolean updateClose(boolean update, CloseState.Event event);

    /**
     * <p>Forcibly closes this stream.</p>
     */
    @Override
    public void close();

    /**
     * <p>Updates the stream send window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the stream send window
     * @return the previous value of the stream send window
     */
    public int updateSendWindow(int delta);

    /**
     * <p>Updates the stream receive window by the given {@code delta}.</p>
     *
     * @param delta the delta value (positive or negative) to add to the stream receive window
     * @return the previous value of the stream receive window
     */
    public int updateRecvWindow(int delta);

    /**
     * <p>Marks this stream as not idle so that the
     * {@link #getIdleTimeout() idle timeout} is postponed.</p>
     */
    public void notIdle();

    /**
     * @return whether the stream is closed remotely.
     * @see #isClosed()
     */
    boolean isRemotelyClosed();

    /**
     * Fail all data queued in the stream and reset
     * demand to 0.
     * @param x the exception to fail the data with.
     * @return true if the end of the stream was reached, false otherwise.
     */
    boolean failAllData(Throwable x);

    /**
     * @return whether this stream has been reset (locally or remotely) or has been failed
     * @see #isReset()
     * @see Listener#onFailure(Stream, int, String, Throwable, Callback)
     */
    boolean isResetOrFailed();

    /**
     * Marks this stream as committed.
     *
     * @see #isCommitted()
     */
    void commit();

    /**
     * @return whether bytes for this stream have been sent to the remote peer.
     * @see #commit()
     */
    boolean isCommitted();

    /**
     * <p>An ordered list of frames belonging to the same stream.</p>
     */
    public static class FrameList
    {
        private final List<StreamFrame> frames;

        /**
         * <p>Creates a frame list of just the given HEADERS frame.</p>
         *
         * @param headers the HEADERS frame
         */
        public FrameList(HeadersFrame headers)
        {
            Objects.requireNonNull(headers);
            this.frames = List.of(headers);
        }

        /**
         * <p>Creates a frame list of the given frames.</p>
         *
         * @param headers the HEADERS frame for the headers
         * @param data the DATA frame for the content, or null if there is no content
         * @param trailers the HEADERS frame for the trailers, or null if there are no trailers
         */
        public FrameList(HeadersFrame headers, DataFrame data, HeadersFrame trailers)
        {
            Objects.requireNonNull(headers);
            ArrayList<StreamFrame> frames = new ArrayList<>(3);
            int streamId = headers.getStreamId();
            if (data != null && data.getStreamId() != streamId)
                throw new IllegalArgumentException("Invalid stream ID for DATA frame " + data);
            if (trailers != null && trailers.getStreamId() != streamId)
                throw new IllegalArgumentException("Invalid stream ID for HEADERS frame " + trailers);
            frames.add(headers);
            if (data != null)
                frames.add(data);
            if (trailers != null)
                frames.add(trailers);
            this.frames = Collections.unmodifiableList(frames);
        }

        /**
         * @return the stream ID of the frames in this list
         */
        public int getStreamId()
        {
            return frames.get(0).getStreamId();
        }

        /**
         * @return a List of non-null frames
         */
        public List<StreamFrame> getFrames()
        {
            return frames;
        }
    }

    public static final class Data
    {
        private final DataFrame frame;
        private final Runnable complete;

        public Data(DataFrame frame, Runnable complete)
        {
            this.frame = frame;
            this.complete = complete;
        }

        public DataFrame frame()
        {
            return frame;
        }

        public void complete()
        {
            complete.run();
        }
    }
}
