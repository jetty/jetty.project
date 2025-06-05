//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.api;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>Represents a QUIC connection to a remote peer.</p>
 * <p>A {@link Session} represents the active part of the connection, and by calling its APIs
 * applications can generate events on the connection.</p>
 * <p>Conversely, {@link Session.Listener} is the passive part of the connection,
 * and has callback methods that are invoked when events happen on the connection.</p>
 */
public interface Session
{
    /**
     * @return the QUIC connection id
     */
    String getId();

    /**
     * <p>Creates a new QUIC stream id, with the given directionality.</p>
     *
     * @param bidirectional whether the stream is bidirectional or unidirectional
     * @return a new QUIC stream id
     */
    long newStreamId(boolean bidirectional);

    /**
     * <p>Creates a new local QUIC stream with the given stream id and listener.</p>
     *
     * @param streamId the QUIC stream id
     * @param listener the listener of stream events
     * @return a new local QUIC stream
     */
    Stream newStream(long streamId, Stream.Listener listener);

    /**
     * @param streamId the stream id
     * @return the QUIC stream with the given stream id
     */
    Stream getStream(long streamId);

    /**
     * <p>Sends a MAX_STREAMS frame on this connection.</p>
     *
     * @param frame the frame to send
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void maxStreams(MaxStreamsFrame frame, Promise.Invocable<Session> promise);

    /**
     * <p>Sends a PING frame on this connection.</p>
     *
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void ping(Promise.Invocable<Session> promise);

    /**
     * <p>Sends a MAX_DATA frame on this connection.</p>
     *
     * @param frame the frame to send
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * frame has been sent
     */
    void maxData(MaxDataFrame frame, Promise.Invocable<Session> promise);

    /**
     * <p>Closes this session with the given {@code CONNECTION_CLOSE} frame.</p>
     * <p>Applications should use this method in conjunction with
     * {@link ConnectionCloseFrame#ConnectionCloseFrame(long, String)}.</p>
     * <p>Differently from {@link #disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)},
     * this method performs close actions inwards, towards the application,
     * that may perform additional actions such as writing to the network,
     * for example close frames for a protocol on top of QUIC.</p>
     * <p>After finishing the inward actions,
     * {@link #disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)} should be
     * called to perform close actions outwards and eventually send
     * the QUIC close frame and finally disconnect at the network level,
     * if necessary.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param promise the {@link Callback} that gets notified when the
     * close is complete
     */
    void close(ConnectionCloseFrame frame, Promise.Invocable<Session> promise);

    /**
     * <p>Disconnects this session, with the given {@code CONNECTION_CLOSE}
     * and failure cause, if any.</p>
     * <p>Differently from {@link #close(ConnectionCloseFrame, Promise.Invocable)},
     * this method performs disconnect actions outwards, towards the
     * network: typically clean-up actions and eventually sends the
     * given QUIC close frame and finally disconnect at the network level,
     * if necessary.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param failure the failure that caused the disconnect, or {@code null}
     * @param promise the {@link Promise.Invocable} that gets notified when the
     * disconnect is complete
     */
    void disconnect(ConnectionCloseFrame frame, Throwable failure, Promise.Invocable<Session> promise);

    /**
     * @return the QUIC streams managed by this session
     */
    Collection<Stream> getStreams();

    /**
     * @return the local {@link SocketAddress} associated with this session
     */
    SocketAddress getLocalSocketAddress();

    /**
     * @return the remote {@link SocketAddress} associated with this session
     */
    SocketAddress getRemoteSocketAddress();

    /**
     * @return the local bidirectional streams max count
     */
    long getLocalBidirectionalMaxStreams();

    /**
     * @return the idle timeout in milliseconds
     */
    long getIdleTimeout();

    /**
     * <p>A {@link Listener} is the passive counterpart of a {@link Session} and
     * receives events happening on an QUIC connection.</p>
     *
     * @see Session
     */
    interface Listener
    {
        /**
         * <p>Callback method invoked to retrieve the QUIC transport parameters.</p>
         * <p>This event may not be emitted for all QUIC implementations.</p>
         *
         * @param session the QUIC session
         * @return the QUIC transport parameters
         */
        default TransportParameters onPrepare(Session session)
        {
            return null;
        }

        /**
         * <p>Callback method invoked when a new session is opened.</p>
         *
         * @param session the QUIC session
         */
        default void onOpen(Session session)
        {
        }

        /**
         * <p>Callback method invoked when the QUIC transport parameters are received.</p>
         * <p>This event may not be emitted for all QUIC implementations.</p>
         *
         * @param session the QUIC session
         * @param parameters the QUIC transport parameters
         */
        default void onTransportParameters(Session session, TransportParameters parameters)
        {
        }

        /**
         * <p>Callback method invoked when receiving a frame that causes the creation of a new stream.</p>
         *
         * @param session the QUIC session
         * @param frame the frame that caused the creation of the stream
         * @return a new {@link Stream.Listener} that handles events for the newly created stream
         */
        default Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            return null;
        }

        /**
         * <p>Callback method invoked when a MAX_STREAMS frame is received.</p>
         *
         * @param session the QUIC session
         * @param frame the MAX_STREAMS frame
         */
        default void onMaxStreams(Session session, MaxStreamsFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when a PING frame is received.</p>
         *
         * @param session the QUIC session
         */
        default void onPing(Session session)
        {
        }

        /**
         * <p>Callback method invoked when a STREAMS_BLOCKED frame is received.</p>
         *
         * @param session the QUIC session
         * @param frame the STREAMS_BLOCKED frame
         */
        default void onStreamsBlocked(Session session, StreamsBlockedFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when a MAX_DATA frame is received.</p>
         *
         * @param session the QUIC session
         * @param frame the MAX_DATA frame
         */
        default void onMaxData(Session session, MaxDataFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when a DATA_BLOCKED frame is received.</p>
         *
         * @param session the QUIC session
         * @param frame the DATA_BLOCKED frame
         */
        default void onDataBlocked(Session session, DataBlockedFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the idle timeout expires.</p>
         *
         * @param session the QUIC session
         * @param failure the idle timeout failure
         * @return {@code true} to close the session, {@code false} to ignore the idle timeout
         * @see #getIdleTimeout()
         */
        default boolean onIdleTimeout(Session session, TimeoutException failure)
        {
            return true;
        }

        /**
         * <p>Callback method invoked when a CONNECTION_CLOSE frame has been received.</p>
         *
         * @param session the QUIC session
         * @param frame the CONNECTION_CLOSE frame
         */
        default void onClose(Session session, ConnectionCloseFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the session has been disconnected.</p>
         *
         * @param session the QUIC session
         */
        default void onDisconnect(Session session)
        {
        }

        /**
         * <p>Factory to create {@link Session.Listener} instances.</p>
         */
        interface Factory
        {
            /**
             * @return a new {@link Session.Listener} instance
             */
            Listener newListener();
        }
    }
}
