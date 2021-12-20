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

package org.eclipse.jetty.http3.api;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;

/**
 * <p>The low-level HTTP/3 API representing a connection with a remote peer.</p>
 * <p>A {@link Session} is the active part of the connection, and by calling its APIs
 * applications can generate events on the connection.</p>
 * <p>Conversely, {@link Session.Listener} is the passive part of the connection,
 * and has callback methods that are invoked when events happen on the connection.</p>
 *
 * @see Client
 * @see Server
 * @see Listener
 */
public interface Session
{
    /**
     * @return the local socket address this session is bound to
     */
    public default SocketAddress getLocalSocketAddress()
    {
        return null;
    }

    /**
     * @return the remote socket address this session is connected to
     */
    public default SocketAddress getRemoteSocketAddress()
    {
        return null;
    }

    /**
     * @return a snapshot of all the streams currently belonging to this session
     */
    public default Collection<Stream> getStreams()
    {
        return Collections.emptyList();
    }

    /**
     * <p>Initiates the shutdown of this session by sending a GOAWAY frame to the other peer.</p>
     *
     * @param graceful whether the shutdown should be graceful
     * @return the {@link CompletableFuture} that gets notified when the frame has been sent
     */
    public default CompletableFuture<Void> goAway(boolean graceful)
    {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * <p>The client-side HTTP/3 API representing a connection with a server.</p>
     * <p>Once a {@link Session} has been obtained, it can be used to make HTTP/3 requests:</p>
     * <pre>
     * Session session = ...;
     * HeadersFrame headersFrame = ...;
     * session.newRequest(headersFrame, new Stream.Listener()
     * {
     *     &#64;Override
     *     public void onResponse(Stream stream, HeadersFrame frame)
     *     {
     *         // Response headers received.
     *     }
     * });
     * </pre>
     *
     * @see Stream
     */
    public interface Client extends Session
    {
        /**
         * <p>Makes a request by creating a HTTP/3 stream and sending the given HEADERS frame.</p>
         *
         * @param frame the HEADERS frame containing the HTTP request headers
         * @param listener the listener that gets notified of stream events
         * @return a CompletableFuture that is notified of the stream creation
         */
        public CompletableFuture<Stream> newRequest(HeadersFrame frame, Stream.Client.Listener listener);

        /**
         * <p>The client-side specific {@link Session.Listener}.</p>
         */
        public interface Listener extends Session.Listener
        {
        }
    }

    /**
     * <p>The server-side HTTP/3 API representing a connection with a client.</p>
     * <p>To receive HTTP/3 request events, see {@link Session.Server.Listener#onRequest(Stream.Server, HeadersFrame)}.</p>
     */
    public interface Server extends Session
    {
        /**
         * <p>The server-side specific {@link Session.Listener}.</p>
         */
        public interface Listener extends Session.Listener
        {
            /**
             * <p>Callback method invoked when a connection has been accepted by the server.</p>
             *
             * @param session the session
             */
            public default void onAccept(Session session)
            {
            }

            /**
             * <p>Callback method invoked when a request is received.</p>
             * <p>Applications should implement this method to process HTTP/3 requests,
             * typically providing an HTTP/3 response via {@link Stream.Server#respond(HeadersFrame)}:</p>
             * <pre>
             * class MyServer implements Session.Server.Listener
             * {
             *     &#64;Override
             *     public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
             *     {
             *         // Send a response.
             *         var response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
             *         stream.respond(new HeadersFrame(response, true));
             *         if (!frame.isLast())
             *             stream.demand();
             *         return null;
             *     }
             * }
             * </pre>
             * <p>If there is request content (indicated by the fact that the HEADERS frame
             * is not the last in the stream), then applications either:</p>
             * <ul>
             *     <li>return {@code null} to indicate that they are not interested in
             *     reading the content</li>
             *     <li><em>must</em> call {@link Stream#demand()} and return a {@link Stream.Server.Listener}
             *     that overrides {@link Stream.Server.Listener#onDataAvailable(Stream.Server)} that reads
             *     and consumes the content.</li>
             * </ul>
             *
             * @param stream the stream associated with the request
             * @param frame the HEADERS frame containing the request headers
             * @return a {@link Stream.Server.Listener} that will be notified of stream events
             * @see Stream.Server.Listener#onDataAvailable(Stream.Server)
             */
            public default Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                return null;
            }
        }
    }

    /**
     * <p>A {@link Listener} is the passive counterpart of a {@link Session} and
     * receives events happening on an HTTP/3 connection.</p>
     *
     * @see Session
     */
    public interface Listener
    {
        /**
         * <p>Callback method invoked just before the initial SETTINGS frame is sent
         * to the remote peer, to gather the configuration settings that the local
         * peer wants to send to the remote peer.</p>
         *
         * @param session the session
         * @return a (possibly empty or null) map containing configuration
         * settings to send to the remote peer.
         */
        public default Map<Long, Long> onPreface(Session session)
        {
            return null;
        }

        /**
         * <p>Callback method invoked when a SETTINGS frame has been received.</p>
         *
         * @param session the session
         * @param frame the SETTINGS frame received
         */
        public default void onSettings(Session session, SettingsFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when a GOAWAY frame has been received.</p>
         *
         * @param session the session
         * @param frame the GOAWAY frame received
         */
        public default void onGoAway(Session session, GoAwayFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the idle timeout has expired.</p>
         *
         * @param session the session
         * @return true to confirm the idle timeout, false to ignore the idle timeout
         */
        public default boolean onIdleTimeout(Session session)
        {
            return true;
        }

        /**
         * <p>Callback method invoked when the underlying transport has been disconnected.</p>
         *
         * @param session the session
         * @param error the disconnect error
         * @param reason the disconnect reason
         */
        public default void onDisconnect(Session session, long error, String reason)
        {
        }

        /**
         * <p>Callback method invoked when a failure has been detected for this session.</p>
         *
         * @param session the session
         * @param error the failure error
         * @param reason the failure reason
         * @param failure the failure
         */
        public default void onFailure(Session session, long error, String reason, Throwable failure)
        {
        }
    }
}
