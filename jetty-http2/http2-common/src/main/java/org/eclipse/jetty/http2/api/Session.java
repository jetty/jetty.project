//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.api;

import java.util.Collection;
import java.util.Map;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Session} represents the client-side endpoint of an HTTP/2 connection to a single origin server.</p>
 * <p>Once a {@link Session} has been obtained, it can be used to open HTTP/2 streams:</p>
 * <pre>
 * Session session = ...;
 * HeadersFrame frame = ...;
 * Promise&lt;Stream&gt; promise = ...
 * session.newStream(frame, promise, new Stream.Listener.Adapter()
 * {
 *     public void onHeaders(Stream stream, HeadersFrame frame)
 *     {
 *         // Reply received
 *     }
 * });
 * </pre>
 * <p>A {@link Session} is the active part of the endpoint, and by calling its API applications can generate
 * events on the connection; conversely {@link Session.Listener} is the passive part of the endpoint, and
 * has callbacks that are invoked when events happen on the connection.</p>
 *
 * @see Session.Listener
 */
public interface Session
{
    /**
     * <p>Sends the given HEADERS {@code frame} to create a new {@link Stream}.</p>
     *
     * @param frame the HEADERS frame containing the HTTP headers
     * @param promise the promise that gets notified of the stream creation
     * @param listener the listener that gets notified of stream events
     */
    void newStream(HeadersFrame frame, Promise<Stream> promise, Stream.Listener listener);

    /**
     * <p>Sends the given list of frames to create a new {@link Stream}.</p>
     *
     * @param frames the list of frames to send
     * @param promise the promise that gets notified of the stream creation
     * @param listener the listener that gets notified of stream events
     */
    void newStream(Stream.FrameList frames, Promise<Stream> promise, Stream.Listener listener);

    /**
     * <p>Sends the given PRIORITY {@code frame}.</p>
     * <p>If the {@code frame} references a {@code streamId} that does not exist
     * (for example {@code 0}), then a new {@code streamId} will be allocated, to
     * support <em>unused anchor streams</em> that act as parent for other streams.</p>
     *
     * @param frame the PRIORITY frame to send
     * @param callback the callback that gets notified when the frame has been sent
     * @return the new stream id generated by the PRIORITY frame, or the stream id
     * that it is already referencing
     */
    int priority(PriorityFrame frame, Callback callback);

    /**
     * <p>Sends the given SETTINGS {@code frame} to configure the session.</p>
     *
     * @param frame the SETTINGS frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    void settings(SettingsFrame frame, Callback callback);

    /**
     * <p>Sends the given PING {@code frame}.</p>
     * <p>PING frames may be used to test the connection integrity and to measure
     * round-trip time.</p>
     *
     * @param frame the PING frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    void ping(PingFrame frame, Callback callback);

    /**
     * <p>Closes the session by sending a GOAWAY frame with the given error code
     * and payload.</p>
     * <p>The GOAWAY frame is sent only once; subsequent or concurrent attempts to
     * close the session will have no effect.</p>
     *
     * @param error the error code
     * @param payload an optional payload (may be null)
     * @param callback the callback that gets notified when the frame has been sent
     * @return true if the frame is being sent, false if the session was already closed
     */
    boolean close(int error, String payload, Callback callback);

    /**
     * @return whether the session is not open
     */
    boolean isClosed();

    /**
     * @return a snapshot of all the streams currently belonging to this session
     */
    Collection<Stream> getStreams();

    /**
     * <p>Retrieves the stream with the given {@code streamId}.</p>
     *
     * @param streamId the stream id of the stream looked for
     * @return the stream with the given id, or null if no such stream exist
     */
    Stream getStream(int streamId);

    /**
     * <p>A {@link Listener} is the passive counterpart of a {@link Session} and
     * receives events happening on an HTTP/2 connection.</p>
     *
     * @see Session
     */
    interface Listener
    {
        /**
         * <p>Callback method invoked:</p>
         * <ul>
         * <li>for clients, just before the preface is sent, to gather the
         * SETTINGS configuration options the client wants to send to the server;</li>
         * <li>for servers, just after having received the preface, to gather
         * the SETTINGS configuration options the server wants to send to the
         * client.</li>
         * </ul>
         *
         * @param session the session
         * @return a (possibly empty or null) map containing SETTINGS configuration
         * options to send.
         */
        Map<Integer, Integer> onPreface(Session session);

        /**
         * <p>Callback method invoked when a new stream is being created upon
         * receiving a HEADERS frame representing an HTTP request.</p>
         * <p>Applications should implement this method to process HTTP requests,
         * typically providing an HTTP response via
         * {@link Stream#headers(HeadersFrame, Callback)}.</p>
         * <p>Applications can detect whether request DATA frames will be arriving
         * by testing {@link HeadersFrame#isEndStream()}. If the application is
         * interested in processing the DATA frames, it must return a
         * {@link Stream.Listener} implementation that overrides
         * {@link Stream.Listener#onData(Stream, DataFrame, Callback)}.</p>
         *
         * @param stream the newly created stream
         * @param frame the HEADERS frame received
         * @return a {@link Stream.Listener} that will be notified of stream events
         */
        Stream.Listener onNewStream(Stream stream, HeadersFrame frame);

        /**
         * <p>Callback method invoked when a SETTINGS frame has been received.</p>
         *
         * @param session the session
         * @param frame the SETTINGS frame received
         */
        void onSettings(Session session, SettingsFrame frame);

        /**
         * <p>Callback method invoked when a PING frame has been received.</p>
         *
         * @param session the session
         * @param frame the PING frame received
         */
        void onPing(Session session, PingFrame frame);

        /**
         * <p>Callback method invoked when a RST_STREAM frame has been received for an unknown stream.</p>
         *
         * @param session the session
         * @param frame the RST_STREAM frame received
         * @see Stream.Listener#onReset(Stream, ResetFrame)
         */
        void onReset(Session session, ResetFrame frame);

        /**
         * <p>Callback method invoked when a GOAWAY frame has been received.</p>
         *
         * @param session the session
         * @param frame the GOAWAY frame received
         * @param callback the callback to notify of the GOAWAY processing
         */
        default void onClose(Session session, GoAwayFrame frame, Callback callback)
        {
            try
            {
                onClose(session, frame);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        void onClose(Session session, GoAwayFrame frame);

        /**
         * <p>Callback method invoked when the idle timeout expired.</p>
         *
         * @param session the session
         * @return whether the session should be closed
         */
        boolean onIdleTimeout(Session session);

        /**
         * <p>Callback method invoked when a failure has been detected for this session.</p>
         *
         * @param session the session
         * @param failure the failure
         * @param callback the callback to notify of failure processing
         */
        default void onFailure(Session session, Throwable failure, Callback callback)
        {
            try
            {
                onFailure(session, failure);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        void onFailure(Session session, Throwable failure);

        /**
         * <p>Empty implementation of {@link Stream.Listener}.</p>
         */
        class Adapter implements Session.Listener
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return null;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return null;
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
            }

            @Override
            public void onPing(Session session, PingFrame frame)
            {
            }

            @Override
            public void onReset(Session session, ResetFrame frame)
            {
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
            }

            @Override
            public boolean onIdleTimeout(Session session)
            {
                return true;
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
            }
        }
    }
}
