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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;

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
     * @return a {@link CompletableFuture} that is notified of the frame send
     */
    CompletableFuture<Session> maxStreams(MaxStreamsFrame frame);

    /**
     * <p>Sends a PING frame on this connection.</p>
     *
     * @return a {@link CompletableFuture} that is notified of the frame send
     */
    CompletableFuture<Session> ping();

    /**
     * <p>Sends a MAX_DATA frame on this connection.</p>
     *
     * @param frame the frame to send
     * @return a {@link CompletableFuture} that is notified of the frame send
     */
    CompletableFuture<Session> maxData(MaxDataFrame frame);

    /**
     * <p>Shuts down this session gracefully.</p>
     *
     * @return a {@link CompletableFuture} that completes when the shutdown completes
     */
    CompletableFuture<Void> shutdown();

    /**
     * <p>Closes this session with the given {@code CONNECTION_CLOSE} frame.</p>
     * <p>Applications should use this method in conjunction with
     * {@link ConnectionCloseFrame#ConnectionCloseFrame(long, String)}.</p>
     * <p>Differently from {@link #disconnect(ConnectionCloseFrame, Throwable)},
     * this method performs close actions inwards, towards the application,
     * that may perform additional actions such as writing to the network,
     * for example close frames for a protocol on top of QUIC.</p>
     * <p>After finishing the inward actions,
     * {@link #disconnect(ConnectionCloseFrame, Throwable)} should be
     * called to perform close actions outwards and eventually send
     * the QUIC close frame.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @return a {@link CompletableFuture} that completes when the frame send completes
     */
    CompletableFuture<Void> close(ConnectionCloseFrame frame);

    /**
     * <p>Disconnects this session, with the given {@code CONNECTION_CLOSE}
     * and failure cause, if any.</p>
     * <p>Differently from {@link #close(ConnectionCloseFrame)},
     * this method performs disconnect actions outwards, towards the
     * network: typically clean-up actions and eventually sends the
     * given QUIC close frame.</p>
     *
     * @param frame the frame carrying the error code and reason
     * @param failure the failure that caused the disconnect, or {@code null}
     * @return a {@link CompletableFuture} that completes when the frame send completes
     */
    CompletableFuture<Void> disconnect(ConnectionCloseFrame frame, Throwable failure);

    Collection<Stream> getStreams();

    SocketAddress getLocalSocketAddress();

    SocketAddress getRemoteSocketAddress();

    long getLocalBidirectionalMaxStreams();

    long getIdleTimeout();

    /**
     * <p>A {@link Listener} is the passive counterpart of a {@link Session} and
     * receives events happening on an QUIC connection.</p>
     *
     * @see Session
     */
    interface Listener
    {
        default TransportParameters onPrepare(Session session)
        {
            return null;
        }

        default void onOpen(Session session)
        {
        }

        default void onTransportParameters(Session session, TransportParameters parameters)
        {
        }

        default Stream.Listener onNewStream(Stream stream)
        {
            return null;
        }

        default void onMaxStreams(Session session, MaxStreamsFrame frame)
        {
        }

        default void onPing(Session session)
        {
        }

        default void onStreamsBlocked(Session session, StreamsBlockedFrame frame)
        {
        }

        default void onMaxData(Session session, MaxDataFrame frame)
        {
        }

        default void onDataBlocked(Session session, DataBlockedFrame frame)
        {
        }

        default boolean onIdleTimeout(Session session, TimeoutException failure)
        {
            return true;
        }

        default void onClose(Session session, ConnectionCloseFrame frame)
        {
        }

        default void onDisconnect(Session session)
        {
        }

        interface Factory
        {
            Listener newListener();
        }
    }
}
