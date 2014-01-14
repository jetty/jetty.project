//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.util.Callback;

/**
 * <p>The internal interface that represents a stream.</p>
 * <p>{@link IStream} contains additional methods used by a SPDY
 * implementation (and not by an application).</p>
 */
public interface IStream extends Stream, Callback
{
    /**
     * <p>Senders of data frames need to know the current window size
     * to determine whether they can send more data.</p>
     *
     * @return the current window size for this stream.
     * @see #updateWindowSize(int)
     */
    public int getWindowSize();

    /**
     * <p>Updates the window size for this stream by the given amount,
     * that can be positive or negative.</p>
     * <p>Senders and recipients of data frames update the window size,
     * respectively, with negative values and positive values.</p>
     *
     * @param delta the signed amount the window size needs to be updated
     * @see #getWindowSize()
     */
    public void updateWindowSize(int delta);

    /**
     * @param listener the stream frame listener associated to this stream
     * as returned by {@link SessionFrameListener#onSyn(Stream, SynInfo)}
     */
    public void setStreamFrameListener(StreamFrameListener listener);

    /**
     * @return the stream frame listener associated to this stream
     */
    public StreamFrameListener getStreamFrameListener();

    /**
     * <p>A stream can be open, {@link #isHalfClosed() half closed} or
     * {@link #isClosed() closed} and this method updates the close state
     * of this stream.</p>
     * <p>If the stream is open, calling this method with a value of true
     * puts the stream into half closed state.</p>
     * <p>If the stream is half closed, calling this method with a value
     * of true puts the stream into closed state.</p>
     *
     * @param close whether the close state should be updated
     * @param local whether the close is local or remote
     */
    public void updateCloseState(boolean close, boolean local);

    /**
     * <p>Processes the given control frame,
     * for example by updating the stream's state or by calling listeners.</p>
     *
     * @param frame the control frame to process
     * @see #process(DataInfo)
     */
    public void process(ControlFrame frame);

    /**
     * <p>Processes the given {@code dataInfo},
     * for example by updating the stream's state or by calling listeners.</p>
     *
     * @param dataInfo the DataInfo to process
     * @see #process(ControlFrame)
     */
    public void process(DataInfo dataInfo);

    /**
     * <p>Associate the given {@link IStream} to this {@link IStream}.</p>
     *
     * @param stream the stream to associate with this stream
     */
    public void associate(IStream stream);

    /**
     * <p>remove the given associated {@link IStream} from this stream</p>
     *
     * @param stream the stream to be removed
     */
    public void disassociate(IStream stream);

    /**
     * <p>Overrides Stream.getAssociatedStream() to return an instance of IStream instead of Stream
     *
     * @see Stream#getAssociatedStream()
     */
    @Override
    public IStream getAssociatedStream();
}
