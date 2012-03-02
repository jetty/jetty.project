/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;

/**
 * <p>The internal interface that represents a stream.</p>
 * <p>{@link IStream} contains additional methods used by a SPDY
 * implementation (and not by an application).</p>
 */
public interface IStream extends Stream
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
     * <p>A stream can be open, {@link #isHalfClosed() half closed} or
     * {@link #isClosed() closed} and this method updates the close state
     * of this stream.</p>
     * <p>If the stream is open, calling this method with a value of true
     * puts the stream into half closed state.</p>
     * <p>If the stream is half closed, calling this method with a value
     * of true puts the stream into closed state.</p>
     *
     * @param close whether the close state should be updated
     */
    public void updateCloseState(boolean close);

    /**
     * <p>Processes the given control frame,
     * for example by updating the stream's state or by calling listeners.</p>
     *
     * @param frame the control frame to process
     * @see #process(DataFrame, ByteBuffer)
     */
    public void process(ControlFrame frame);

    /**
     * <p>Processes the give data frame along with the given byte buffer,
     * for example by updating the stream's state or by calling listeners.</p>
     *
     * @param frame the data frame to process
     * @param data the byte buffer to process
     * @see #process(ControlFrame)
     */
    public void process(DataFrame frame, ByteBuffer data);
}
