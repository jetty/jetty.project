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
import java.util.EventListener;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Callback;

/// Represents a QUIC connection to a remote peer.
///
/// A [Session] represents the active part of the connection, and by calling
/// its APIs applications generate events on the connection.
///
/// Conversely, [Session.Listener] is the passive part of the connection,
/// and has callback methods that are invoked when events happen on the connection.
public interface Session
{
    /// @return the QUIC connection id
    String getId();

    /// Creates a new QUIC stream id, with the given directionality.
    ///
    /// @param bidirectional whether the stream is bidirectional or unidirectional
    /// @return a new QUIC stream id
    long newStreamId(boolean bidirectional);

    /// Creates a new local QUIC stream with the given stream id and listener.
    /// No communication happens with the other peer until [Stream] methods are
    /// called to send frames, such as [Stream#data(boolean, RetainableByteBuffer, Callback)].
    ///
    /// @param streamId the QUIC stream id
    /// @param listener the listener of stream events
    /// @return a new local QUIC stream
    Stream newStream(long streamId, Stream.Listener listener);

    /// @param streamId the stream id
    /// @return the QUIC stream with the given stream id
    Stream getStream(long streamId);

    /// @return the QUIC streams managed by this session
    Collection<Stream> getStreams();

    /// Sends a MAX_STREAMS frame on this session.
    ///
    /// @param callback the [Callback] that gets notified when the frame has been sent
    void maxStreams(long maxStreams, boolean bidirectional, Callback callback);

    /// Sends a PING frame on this connection.
    ///
    /// @param callback the [Callback] that gets notified when the frame has been sent
    void ping(Callback callback);

    /// Sends a MAX_DATA frame on this connection.
    ///
    /// @param callback the [Callback] that gets notified when the frame has been sent
    void maxData(long maxData, Callback callback);

    /// Closes this session with the given application error and reason.
    ///
    /// Differently from [#disconnect(long, String, Throwable, Callback)],
    /// this method performs close actions upwards, towards the application,
    /// that may perform additional actions such as writing to the network
    /// (for example, close frames for a protocol on top of QUIC).
    ///
    /// After finishing the upward actions,
    /// [#disconnect(long, String, Throwable, Callback)] is called,
    /// with the given application error and reason.
    /// Applications may override the error and reason by calling themselves
    /// [#disconnect(long, String, Throwable, Callback)] with different
    /// parameters.
    ///
    /// @param appError the application error code for the close
    /// @param reason the reason for the close
    /// @param callback the [Callback] that gets notified when the close is complete
    void close(long appError, String reason, Callback callback);

    /// Disconnects this session, with the given application error and reason.
    ///
    /// This method eventually sends a [ConnectionCloseFrame] of type `0x1D`.
    ///
    /// Differently from [#close(long, String, Callback)],
    /// this method performs disconnect actions downwards, towards the
    /// network: typically cleanup actions, sending the close frame
    /// and finally disconnect at the network level, if necessary.
    ///
    /// @param failure the failure which caused the disconnection, or `null`
    /// @param callback the [Callback] that gets notified when the disconnect is complete
    void disconnect(long appError, String reason, Throwable failure, Callback callback);

    /// @return the local [SocketAddress] associated with this session
    SocketAddress getLocalSocketAddress();

    /// @return the remote [SocketAddress] associated with this session
    SocketAddress getRemoteSocketAddress();

    /// @return the local bidirectional streams max count
    long getBidirectionalLocalStreamMaxCount();

    /// @return the idle timeout in milliseconds
    long getIdleTimeout();

    /// A [Listener] is the passive counterpart of a [Session] and
    /// receives events happening on an QUIC connection.
    ///
    /// @see Session
    interface Listener extends EventListener
    {
        /// Callback method invoked when the session is created.
        ///
        /// @param session the QUIC session
        default void onCreated(Session session)
        {
        }

        /// Callback method invoked to customize the local QUIC transport parameters.
        ///
        /// @param session the QUIC session
        /// @param transportParameters the local [TransportParameters] to modify
        default void onPrepare(Session session, TransportParameters transportParameters)
        {
        }

        /// Callback method invoked when the remote QUIC transport parameters are received.
        ///
        /// @param session the QUIC session
        /// @param parameters the QUIC transport parameters received from the remote peer
        default void onTransportParameters(Session session, TransportParameters parameters)
        {
        }

        /// Callback method invoked when a new session is opened.
        ///
        /// A session is opened when the TLS handshake is _confirmed_,
        /// as defined in RFC-9001 #4.1.2.
        ///
        /// @param session the QUIC session
        default void onOpen(Session session)
        {
        }

        /// Callback method invoked when receiving a frame that causes the creation of a new stream.
        ///
        /// @param session the QUIC session
        /// @param frame the frame that caused the creation of the stream
        /// @return a new [Stream.Listener] that handles events for the newly created stream
        default Stream.Listener onNewStream(Session session, Frame.WithStreamId frame)
        {
            return Stream.Listener.DEFAULT;
        }

        /// Callback method invoked when a MAX_STREAMS frame is received.
        ///
        /// @param session the QUIC session
        /// @param frame the MAX_STREAMS frame
        default void onMaxStreams(Session session, MaxStreamsFrame frame)
        {
        }

        /// Callback method invoked when a PING frame is received.
        ///
        /// @param session the QUIC session
        default void onPing(Session session)
        {
        }

        /// Callback method invoked when a STREAMS_BLOCKED frame is received.
        ///
        /// @param session the QUIC session
        /// @param frame the STREAMS_BLOCKED frame
        default void onStreamsBlocked(Session session, StreamsBlockedFrame frame)
        {
        }

        /// Callback method invoked when a MAX_DATA frame is received.
        ///
        /// @param session the QUIC session
        /// @param frame the MAX_DATA frame
        default void onMaxData(Session session, MaxDataFrame frame)
        {
        }

        /// Callback method invoked when a DATA_BLOCKED frame is received.
        ///
        /// @param session the QUIC session
        /// @param frame the DATA_BLOCKED frame
        default void onDataBlocked(Session session, DataBlockedFrame frame)
        {
        }

        /// Callback method invoked when a CONNECTION_CLOSE frame has been received.
        ///
        /// @param session the QUIC session
        /// @param frame the CONNECTION_CLOSE frame
        default void onClose(Session session, ConnectionCloseFrame frame)
        {
        }

        /// Callback method invoked when a failure has been detected.
        ///
        /// @param session the QUIC session
        /// @param failure the failure
        default void onFailure(Session session, Throwable failure)
        {
        }

        /// Callback method invoked when the session has been disconnected.
        ///
        /// @param session the QUIC session
        /// @param frame the CONNECTION_CLOSE frame that has been sent
        default void onDisconnect(Session session, ConnectionCloseFrame frame)
        {
        }

        /// Factory to create [Session.Listener] instances.
        interface Factory
        {
            /// @return a new [Session.Listener] instance
            Listener newListener();
        }
    }
}
