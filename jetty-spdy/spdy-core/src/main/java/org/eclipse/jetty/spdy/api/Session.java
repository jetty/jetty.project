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

package org.eclipse.jetty.spdy.api;

import java.net.InetSocketAddress;
import java.util.EventListener;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Session} represents the client-side endpoint of a SPDY connection to a single origin server.</p>
 * <p>Once a {@link Session} has been obtained, it can be used to open SPDY streams:</p>
 * <pre>
 * Session session = ...;
 * SynInfo synInfo = new SynInfo(true);
 * session.push(synInfo, new Stream.FrameListener.Adapter()
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
     * <p>Callers may use the returned Stream for example, to send data frames.</p>
     *
     * @param synInfo  the metadata to send on stream creation
     * @param listener the listener to invoke when events happen on the stream just created
     * @return the stream that will be created
     * @see #syn(SynInfo, StreamFrameListener, Promise)
     */
    public Stream syn(SynInfo synInfo, StreamFrameListener listener) throws ExecutionException, InterruptedException, TimeoutException;

    /**
     * <p>Sends asynchronously a SYN_FRAME to create a new {@link Stream SPDY stream}.</p>
     * <p>Callers may pass a non-null completion callback to be notified of when the
     * stream has been created and use the stream, for example, to send data frames.</p>
     *
     *
     * @param synInfo  the metadata to send on stream creation
     * @param listener the listener to invoke when events happen on the stream just created
     * @param promise  the completion callback that gets notified of stream creation
     * @see #syn(SynInfo, StreamFrameListener)
     */
    public void syn(SynInfo synInfo, StreamFrameListener listener, Promise<Stream> promise);

    /**
     * <p>Sends synchronously a RST_STREAM to abort a stream.</p>
     *
     * @param rstInfo the metadata to reset the stream
     * @see #rst(RstInfo, Callback)
     */
    public void rst(RstInfo rstInfo) throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * <p>Sends asynchronously a RST_STREAM to abort a stream.</p>
     * <p>Callers may pass a non-null completion callback to be notified of when the
     * reset has been actually sent.</p>
     *
     * @param rstInfo the metadata to reset the stream
     * @param callback the completion callback that gets notified of reset's send
     * @see #rst(RstInfo)
     */
    public void rst(RstInfo rstInfo, Callback callback);

    /**
     * <p>Sends synchronously a SETTINGS to configure the SPDY connection.</p>
     *
     * @param settingsInfo the metadata to send
     * @see #settings(SettingsInfo, Callback)
     */
    public void settings(SettingsInfo settingsInfo) throws ExecutionException, InterruptedException, TimeoutException;

    /**
     * <p>Sends asynchronously a SETTINGS to configure the SPDY connection.</p>
     * <p>Callers may pass a non-null completion callback to be notified of when the
     * settings has been actually sent.</p>
     *
     *
     * @param settingsInfo the metadata to send
     * @param callback      the completion callback that gets notified of settings' send
     * @see #settings(SettingsInfo)
     */
    public void settings(SettingsInfo settingsInfo, Callback callback);

    /**
     * <p>Sends synchronously a PING, normally to measure round-trip time.</p>
     *
     * @see #ping(PingInfo, Promise)
     * @param pingInfo
     */
    public PingResultInfo ping(PingInfo pingInfo) throws ExecutionException, InterruptedException, TimeoutException;

    /**
     * <p>Sends asynchronously a PING, normally to measure round-trip time.</p>
     * <p>Callers may pass a non-null completion callback to be notified of when the
     * ping has been actually sent.</p>
     *
     * @param pingInfo
     * @param promise the completion callback that gets notified of ping's send
     * @see #ping(PingInfo)
     */
    public void ping(PingInfo pingInfo, Promise<PingResultInfo> promise);

    /**
     * <p>Closes gracefully this session, sending a GO_AWAY frame and then closing the TCP connection.</p>
     *
     * @see #goAway(GoAwayInfo, Callback)
     * @param goAwayInfo
     */
    public void goAway(GoAwayInfo goAwayInfo) throws ExecutionException, InterruptedException, TimeoutException;

    /**
     * <p>Closes gracefully this session, sending a GO_AWAY frame and then closing the TCP connection.</p>
     * <p>Callers may pass a non-null completion callback to be notified of when the
     * go away has been actually sent.</p>
     *
     * @param goAwayInfo
     * @param callback the completion callback that gets notified of go away's send
     * @see #goAway(GoAwayInfo)
     */
    public void goAway(GoAwayInfo goAwayInfo, Callback callback);

    /**
     * @return a snapshot of the streams currently active in this session
     * @see #getStream(int)
     */
    public Set<Stream> getStreams();

    /**
     * @param streamId the id of the stream to retrieve
     * @return the stream with the given stream id
     * @see #getStreams()
     */
    public Stream getStream(int streamId);

    /**
     * @param key the attribute key
     * @return an arbitrary object associated with the given key to this session
     * @see #setAttribute(String, Object)
     */
    public Object getAttribute(String key);

    /**
     * @param key   the attribute key
     * @param value an arbitrary object to associate with the given key to this session
     * @see #getAttribute(String)
     * @see #removeAttribute(String)
     */
    public void setAttribute(String key, Object value);

    /**
     * @param key the attribute key
     * @return the arbitrary object associated with the given key to this session
     * @see #setAttribute(String, Object)
     */
    public Object removeAttribute(String key);

    /**
     * @return the local address of the underlying endpoint
     */
    public InetSocketAddress getLocalAddress();

    /**
     * @return the remote address of the underlying endpoint
     */
    public InetSocketAddress getRemoteAddress();

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
