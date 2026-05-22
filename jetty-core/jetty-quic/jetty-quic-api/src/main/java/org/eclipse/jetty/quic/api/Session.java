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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

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
    /// called to send frames, such as [Stream#data(boolean, RetainableByteBuffer, Promise.Invocable)].
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
    /// @param frame the frame to send
    /// @param promise the [Promise.Invocable] that gets notified when the frame has been sent
    void maxStreams(MaxStreamsFrame frame, Promise.Invocable<Session> promise);

    /// Sends a PING frame on this connection.
    ///
    /// @param promise the [Promise.Invocable] that gets notified when the frame has been sent
    void ping(Promise.Invocable<Session> promise);

    /// Sends a MAX_DATA frame on this connection.
    ///
    /// @param frame the frame to send
    /// @param promise the [Promise.Invocable] that gets notified when the frame has been sent
    void maxData(MaxDataFrame frame, Promise.Invocable<Session> promise);

    /// Closes this session with the given `CONNECTION_CLOSE` frame.
    ///
    /// Applications should use this method in conjunction with
    /// [ConnectionCloseFrame#ConnectionCloseFrame(long, String)].
    ///
    /// Differently from [#disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)],
    /// this method performs close actions upwards, towards the application,
    /// that may perform additional actions such as writing to the network
    /// (for example, close frames for a protocol on top of QUIC).
    ///
    /// After finishing the upward actions,
    /// [#disconnect(ConnectionCloseFrame, Throwable, Promise.Invocable)] should be
    /// called to perform close actions downwards and eventually send
    /// the QUIC close frame and finally disconnect at the network level,
    /// if necessary.
    ///
    /// @param frame the frame carrying the error code and reason
    /// @param promise the [Callback] that gets notified when the close is complete
    void close(ConnectionCloseFrame frame, Promise.Invocable<Session> promise);

    /// Disconnects this session, with the given `CONNECTION_CLOSE`
    /// and failure cause, if any.
    ///
    /// Differently from [#close(ConnectionCloseFrame, Promise.Invocable)],
    /// this method performs disconnect actions downwards, towards the
    /// network: typically cleanup actions and eventually sends the
    /// given QUIC close frame and finally disconnect at the network level,
    /// if necessary.
    ///
    /// @param frame the frame carrying the error code and reason
    /// @param failure the failure which caused the disconnection, or `null`
    /// @param promise the [Promise.Invocable] that gets notified when the disconnect is complete
    void disconnect(ConnectionCloseFrame frame, Throwable failure, Promise.Invocable<Session> promise);

    /// @return the local [SocketAddress] associated with this session
    SocketAddress getLocalSocketAddress();

    /// @return the remote [SocketAddress] associated with this session
    SocketAddress getRemoteSocketAddress();

    /// @return the local bidirectional streams max count
    long getLocalBidirectionalMaxStreams();

    /// @return the idle timeout in milliseconds
    long getIdleTimeout();

    /// A [Listener] is the passive counterpart of a [Session] and
    /// receives events happening on an QUIC connection.
    ///
    /// @see Session
    interface Listener
    {
        /// Callback method invoked to customize the local QUIC transport parameters.
        ///
        /// This event may not be emitted for all QUIC implementations.
        ///
        /// @param session the QUIC session
        /// @param transportParameters the local [TransportParameters] to modify
        default void onPrepare(Session session, TransportParameters transportParameters)
        {
        }

        /// Callback method invoked when the remote QUIC transport parameters are received.
        ///
        /// This event may not be emitted for all QUIC implementations.
        ///
        /// @param session the QUIC session
        /// @param parameters the QUIC transport parameters received from the remote peer
        default void onTransportParameters(Session session, TransportParameters parameters)
        {
        }

        /// Callback method invoked when a new session is opened.
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

        /// Callback method invoked when the idle timeout expires.
        ///
        /// @param session the QUIC session
        /// @param failure the idle timeout failure
        /// @return `true` to close the session, `false` to ignore the idle timeout
        /// @see #getIdleTimeout()
        // TODO: remove this method, as QUIC idle timeout cannot be ignored:
        //  when they happen, they are fatal.
        //  The solution is to not make them happen via a keep-alive mechanism
        //  or by setting them to 0, and have the upper layer configured with
        //  an idle timeout, which will then trigger the upper protocol close.
        //  If QUIC detects an idle timeout, for the upper layer is a fatal failure,
        //  and the upper layer cannot return "false" to indicate to ignore the idle timeout.
        default boolean onIdleTimeout(Session session, TimeoutException failure)
        {
            return true;
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
        default void onDisconnect(Session session)
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
