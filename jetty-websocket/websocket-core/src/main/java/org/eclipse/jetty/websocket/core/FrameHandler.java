//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * Interface for local WebSocket Endpoint Frame handling.
 *
 * <p>
 * This is the receiver of Parsed WebSocketFrames.
 * </p>
 */
public interface FrameHandler
{
    // TODO: have conversation about "throws Exception" vs "throws WebSocketException" vs "throws Throwable" in below signatures.

    /**
     * Connection is being opened.
     * <p>
     *     FrameHandler can write during this call, but will not receive frames until
     *     the onOpen() completes.
     * </p>
     * @param channel the channel associated with this connection.
     * @throws Exception if unable to open. TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onOpen(Channel channel) throws Exception;

    /**
     * Receiver of all DATA Frames (Text, Binary, Continuation), and all CONTROL Frames (Ping, Pong, Close)

     * @param frame the raw frame
     * @param callback the callback to indicate success in processing frame (or failure)
     * @throws Exception if unable to process the frame.  TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onFrame(Frame frame, Callback callback) throws Exception;

    /**
     * This is the Close Handshake Complete event.
     * <p>
     *     The connection is now closed, no reading or writing is possible anymore.
     *     Implementations of FrameHandler can cleanup their resources for this connection now.
     * </p>
     *
     * @param closeStatus the close status received from remote, or in the case of abnormal closure from local.
     * @throws Exception if unable to complete the closure. TODO: what happens if an exception occurs here?
     */
    void onClosed(CloseStatus closeStatus) throws Exception;

    /**
     * An error has occurred or been detected in websocket-core and being reported to FrameHandler.
     *
     * @param cause the reason for the error
     * @throws Exception if unable to process the error. TODO: what happens if an exception occurs here?  does any error means a connection is (or will be) closed?
     */
    void onError(Throwable cause) throws Exception;

    /**
     * Represents the outgoing WebSocketFrames.
     */
    interface Channel extends OutgoingFrames // TODO: want AutoCloseable (easier testing)
    {
        /**
         * TODO: might be redundant (evaluate API usage, possible to access from HandshakeResponse concepts)
         * The negotiated WebSocket Subprotocol for this channel.
         *
         * @return the negotiated WebSocket Subprotocol for this channel.
         */
        String getSubprotocol();

        /**
         * TODO: might be redundant (evaluate API usage, possible to access from HandshakeResponse concepts)
         * The negotiated WebSocket Extension Configurations for this channel.
         *
         * @return the list of Extension Configurations for this channel.
         */
        List<ExtensionConfig> getExtensionConfig();

        /**
         * TODO: need implementation
         *
         * Issue a harsh abort of the underlying connection.
         * <p>
         * This will terminate the connection, without sending a websocket close frame.
         * No WebSocket Protocol close handshake will be performed.
         * </p>
         * <p>
         * Once called, any read/write activity on the websocket from this point will be indeterminate.
         * This can result in the {@link #onError(Throwable)} event being called indicating any issue that arises.
         * </p>
         * <p>
         * Once the underlying connection has been determined to be closed, the {@link #onClosed(CloseStatus)} event will be called.
         * </p>
         */
        void abort();
        
        WebSocketBehavior getBehavior();

        /**
         * The Local Socket Address for the connection
         * <p>
         *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
         *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
         *     without supporting {@link InetSocketAddress}
         * </p>
         *
         * @return the SocketAddress for the local connection, or null if not supported by Channel
         */
        SocketAddress getLocalAddress();

        /**
         * The Remote Socket Address for the connection
         * <p>
         *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
         *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
         *     without supporting {@link InetSocketAddress}
         * </p>
         *
         * @return the SocketAddress for the remote connection, or null if not supported by Channel
         */
        SocketAddress getRemoteAddress();

        boolean isOpen();

        // TODO: possible configurable for AsyncSendTimeout (JSR356-ism for async write)

        /**
         * Get the Idle Timeout in the unit provided.
         *
         * @param units the time unit interested in.
         * @return the idle timeout in the unit provided (or -1 if unset / infinite)
         * TODO: is this how we want to handle infinite timeout?
         */
        long getIdleTimeout(TimeUnit units);

        /**
         * Set the Idle Timeout in the unit provided.
         *
         * @param timeout the timeout duration (if -1, the timeout is infinite)
         * @param units the time unit
         * TODO: is this how we want to handle infinite timeout?
         * TODO: what to do if someone sets a ridiculous timeout? eg: (600,000, DAYS) allow it?
         */
        void setIdleTimeout(long timeout, TimeUnit units);

        /**
         * If using BatchMode.ON or BatchMode.AUTO, trigger a flush of enqueued / batched frames.
         *
         * @param callback the callback to track close frame sent (or failed)
         * TODO: what is the expected reaction to Callback.failed()?
         */
        void flushBatch(Callback callback);

        /**
         * Initiate close handshake, no payload (no declared status code or reason phrase)
         *
         * @param callback the callback to track close frame sent (or failed)
         * TODO: what is the expected reaction to Callback.failed() ?
         */
        void close(Callback callback);

        /**
         * Initiate close handshake with provide status code and optional reason phrase.
         *
         * @param statusCode the status code (should be a valid status code that can be sent)
         * @param reason optional reason phrase (will be truncated automatically by implementation to fit within limits of protocol)
         * @param callback the callback to track close frame sent (or failed)
         * TODO: what is the expected reaction to Callback.failed() ?
         */
        void close(int statusCode, String reason, Callback callback);
    }

    // FIXED: Want access to common ByteBufferPool - negotiator has getBufferPool() now
    // TODO: Want access to common Executor used by core for reuse in APIs (either read-only, or pushed into core) - connection has Executor now
}
