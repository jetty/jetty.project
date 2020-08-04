//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

import java.io.Closeable;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.Frame;
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

    /**
     * @param listener the {@link org.eclipse.jetty.http2.api.Stream.Listener} associated with this stream
     * @see #getListener()
     */
    public void setListener(Listener listener);

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
     * @return whether this stream has been reset (locally or remotely) or has been failed
     * @see #isReset()
     * @see Listener#onFailure(Stream, int, String, Throwable, Callback)
     */
    boolean isResetOrFailed();
}
