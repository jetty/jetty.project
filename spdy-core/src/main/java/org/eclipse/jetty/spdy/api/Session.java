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

package org.eclipse.jetty.spdy.api;

import java.util.EventListener;
import java.util.List;

/**
 * <p>A {@link Session} represents the client-side endpoint of a SPDY connection to a single origin server.</p>
 * <p>Once a {@link Session} has been obtained, it can be used to open SPDY streams:</p>
 * <pre>
 * Session session = ...;
 * SynInfo synInfo = new SynInfo(true);
 * session.syn(synInfo, new Stream.FrameListener.Adapter()
 * {
 *     public void onReply(Stream stream, ReplyInfo replyInfo)
 *     {
 *         // Stream reply received
 *     }
 * });
 * </pre>
 * <p>A {@link Session} is the active part of the endpoint, and by calling its API applications can generate
 * events on the connection; conversely {@link SessionFrameListener} is the passive part of the endpoint, and
 * has callbacks that are invoked when events happen on the connection.</p>
 *
 * @see SessionFrameListener
 */
public interface Session
{
    /**
     * @return the SPDY protocol version used by this session
     */
    public short getVersion();

    /**
     * <p>Registers the given {@code listener} to be notified of session events.</p>
     *
     * @param listener the listener to register
     * @see #removeListener(Listener)
     */
    public void addListener(Listener listener);

    /**
     * <p>Deregisters the give {@code listener} from being notified of session events.</p>
     *
     * @param listener the listener to deregister
     * @see #addListener(Listener)
     */
    public void removeListener(Listener listener);

    /**
     * <p>Sends a SYN_FRAME to create a new {@link Stream SPDY stream}.</p>
     *
     * @param synInfo  the metadata to send on stream creation
     * @param listener the listener to invoke when events happen on the stream just created
     * @return the stream just created
     */
    public Stream syn(SynInfo synInfo, StreamFrameListener listener);

    /**
     * <p>Sends a RST_STREAM to abort a stream.</p>
     *
     * @param rstInfo the metadata to reset the stream
     */
    public void rst(RstInfo rstInfo);

    /**
     * <p>Sends a SETTINGS to configure the SPDY connection.</p>
     *
     * @param settingsInfo the metadata to send
     */
    public void settings(SettingsInfo settingsInfo);

    /**
     * <p>Sends a PING, normally to measure round-trip time.</p>
     *
     * @return the metadata sent
     */
    public PingInfo ping();

    /**
     * <p>Closes gracefully this session, sending a GO_AWAY frame and then closing the TCP connection.</p>
     */
    public void goAway();

    /**
     * <p>Initiates the flush of data to the other peer.</p>
     * <p>Note that the flush may do nothing if, for example, there is nothing to flush, or
     * if the data to be flushed belong to streams that have their flow-control stalled.</p>
     */
    public void flush();

    /**
     * @return the streams currently active in this session
     */
    public List<Stream> getStreams();

    /**
     * <p>Super interface for listeners with callbacks that are invoked on specific session events.</p>
     */
    public interface Listener extends EventListener
    {
    }

    /**
     * <p>Specialized listener that is invoked upon creation and removal of streams.</p>
     */
    public interface StreamListener extends Listener
    {
        /**
         * <p>Callback invoked when a new SPDY stream is created.</p>
         *
         * @param stream the stream just created
         */
        public void onStreamCreated(Stream stream);

        /**
         * <p>Callback invoked when a SPDY stream is closed.</p>
         *
         * @param stream the stream just closed.
         */
        public void onStreamClosed(Stream stream);

        /**
         * <p>Empty implementation of {@link StreamListener}.</p>
         */
        public static class Adapter implements StreamListener
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
            }

            @Override
            public void onStreamClosed(Stream stream)
            {
            }
        }
    }
}
