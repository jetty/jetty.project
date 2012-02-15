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
import java.util.concurrent.Future;

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
     * <p>Sends asynchronously a SYN_FRAME to create a new {@link Stream SPDY stream}.</p>
     * <p>Callers may use the returned future to wait for the stream to be created, and
     * use the stream, for example, to send data frames.</p>
     *
     * @param synInfo  the metadata to send on stream creation
     * @param listener the listener to invoke when events happen on the stream just created
     * @return a future for the stream that will be created
     * @see #syn(SynInfo, StreamFrameListener, ResultHandler)
     */
    public Future<Stream> syn(SynInfo synInfo, StreamFrameListener listener);

    /**
     * <p>Sends asynchronously a SYN_FRAME to create a new {@link Stream SPDY stream}.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * stream has been created and use the stream, for example, to send data frames.</p>
     *
     * @param synInfo  the metadata to send on stream creation
     * @param listener the listener to invoke when events happen on the stream just created
     * @param handler  the completion handler that gets notified of stream creation
     * @see #syn(SynInfo, StreamFrameListener)
     */
    public void syn(SynInfo synInfo, StreamFrameListener listener, ResultHandler<Stream> handler);

    /**
     * <p>Sends asynchronously a RST_STREAM to abort a stream.</p>
     * <p>Callers may use the returned future to wait for the reset to be sent.</p>
     *
     * @param rstInfo the metadata to reset the stream
     * @return a future to wait for the reset to be sent
     */
    public Future<Void> rst(RstInfo rstInfo);

    /**
     * <p>Sends asynchronously a RST_STREAM to abort a stream.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * reset has been actually sent.</p>
     *
     * @param rstInfo the metadata to reset the stream
     * @param handler  the completion handler that gets notified of reset's send
     */
    public void rst(RstInfo rstInfo, Handler handler);

    /**
     * <p>Sends asynchronously a SETTINGS to configure the SPDY connection.</p>
     * <p>Callers may use the returned future to wait for the settings to be sent.</p>
     *
     * @param settingsInfo the metadata to send
     * @return a future to wait for the settings to be sent
     */
    public Future<Void> settings(SettingsInfo settingsInfo);

    /**
     * <p>Sends asynchronously a SETTINGS to configure the SPDY connection.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * settings has been actually sent.</p>
     *
     * @param settingsInfo the metadata to send
     * @param handler  the completion handler that gets notified of settings' send
     */
    public void settings(SettingsInfo settingsInfo, Handler handler);

    /**
     * <p>Sends asynchronously a PING, normally to measure round-trip time.</p>
     * <p>Callers may use the returned future to wait for the ping to be sent.</p>
     *
     * @return a future for the metadata sent
     */
    public Future<PingInfo> ping();

    /**
     * <p>Sends asynchronously a PING, normally to measure round-trip time.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * ping has been actually sent.</p>
     *
     * @param handler  the completion handler that gets notified of ping's send
     */
    public void ping(ResultHandler<PingInfo> handler);

    /**
     * <p>Closes gracefully this session, sending a GO_AWAY frame and then closing the TCP connection.</p>
     * <p>Callers may use the returned future to wait for the go away to be sent.</p>
     *
     * @return a future to wait for the go away to be sent
     */
    public Future<Void> goAway();

    /**
     * <p>Closes gracefully this session, sending a GO_AWAY frame and then closing the TCP connection.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * go away has been actually sent.</p>
     *
     * @param handler  the completion handler that gets notified of go away's send
     */
    public void goAway(Handler handler);

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
/*
    public static abstract class SynHandler extends Promise<Stream>
    {
        @Override
        public final void completed()
        {
            // Applications should not override this method, but the one below
        }

        public abstract void completed(Stream stream);
    }

    public static abstract class PingHandler extends Promise<PingInfo>
    {
        @Override
        public final void completed()
        {
            // Applications should not override this method, but the one below
        }

        public abstract void completed(PingInfo stream);
    }
*/
}
