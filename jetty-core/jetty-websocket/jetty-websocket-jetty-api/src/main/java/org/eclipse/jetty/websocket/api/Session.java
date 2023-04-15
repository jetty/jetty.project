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

package org.eclipse.jetty.websocket.api;

import java.io.Closeable;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Session represents an active link of communications with a Remote WebSocket Endpoint.
 */
public interface Session extends Configurable, Closeable
{
    /**
     * <p>Explicitly demands for WebSocket frames.</p>
     * <p>This method should be called only when the WebSocket endpoint is not
     * demanding automatically, as defined by {@link WebSocket#autoDemand()}
     * and {@link Listener.AutoDemanding}.</p>
     */
    void demand();

    /**
     * <p>Initiates the asynchronous send of a BINARY message, notifying
     * the given callback when the message send is completed, either
     * successfully or with a failure.</p>
     *
     * @param buffer the message bytes to send
     * @param callback callback to notify when the send operation is complete
     */
    void sendBinary(ByteBuffer buffer, Callback callback);

    /**
     * <p>Initiates the asynchronous send of a BINARY frame, possibly part
     * of a larger binary message, notifying the given callback when the frame
     * send is completed, either successfully or with a failure.</p>
     * <p>Non-final frames must be sent with the parameter {@code last=false}.
     * The final frame must be sent with {@code last=true}.</p>
     *
     * @param buffer the frame bytes to send
     * @param last whether this is the last frame of the message
     * @param callback callback to notify when the send operation is complete
     */
    void sendPartialBinary(ByteBuffer buffer, boolean last, Callback callback);

    /**
     * <p>Initiates the asynchronous send of a TEXT message, notifying
     * the given callback when the message send is completed, either
     * successfully or with a failure.</p>
     *
     * @param text the message text to send
     * @param callback callback to notify when the send operation is complete
     */
    void sendText(String text, Callback callback);

    /**
     * <p>Initiates the asynchronous send of a TEXT frame, possibly part
     * of a larger binary message, notifying the given callback when the frame
     * send is completed, either successfully or with a failure.</p>
     * <p>Non-final frames must be sent with the parameter {@code last=false}.
     * The final frame must be sent with {@code last=true}.</p>
     *
     * @param text the frame text to send
     * @param last whether this is the last frame of the message
     * @param callback callback to notify when the send operation is complete
     */
    void sendPartialText(String text, boolean last, Callback callback);

    /**
     * <p>Initiates the asynchronous send of a PING frame, notifying the given
     * callback when the frame send is completed, either successfully or with
     * a failure.</p>
     *
     * @param applicationData the data to be carried in the PING frame
     * @param callback callback to notify when the send operation is complete
     */
    void sendPing(ByteBuffer applicationData, Callback callback);

    /**
     * <p>Initiates the asynchronous send of a PONG frame, notifying the given
     * callback when the frame send is completed, either successfully or with
     * a failure.</p>
     *
     * @param applicationData the data to be carried in the PONG frame
     * @param callback callback to notify when the send operation is complete
     */
    void sendPong(ByteBuffer applicationData, Callback callback);

    /**
     * <p>Equivalent to {@code close(StatusCode.NORMAL, null, Callback.NOOP)}.</p>
     *
     * @see #close(int, String, Callback)
     * @see #disconnect()
     */
    @Override
    default void close()
    {
        close(StatusCode.NORMAL, null, Callback.NOOP);
    }

    /**
     * <p>Sends a websocket CLOSE frame, with status code and reason, notifying
     * the given callback when the frame send is completed, either successfully
     * or with a failure.</p>
     *
     * @param statusCode the status code
     * @param reason the (optional) reason
     * @param callback callback to notify when the send operation is complete
     * @see StatusCode
     * @see #close()
     * @see #disconnect()
     */
    void close(int statusCode, String reason, Callback callback);

    /**
     * <p>Abruptly closes the WebSocket connection without sending a CLOSE frame.</p>
     *
     * @see #close(int, String, Callback)
     */
    void disconnect();

    /**
     * @return the local SocketAddress for the connection, if available
     */
    SocketAddress getLocalSocketAddress();

    /**
     * @return the remote SocketAddress for the connection, if available
     */
    SocketAddress getRemoteSocketAddress();

    /**
     * <p>Returns the version of the WebSocket protocol currently being used.</p>
     * <p>This is taken as the value of the {@code Sec-WebSocket-Version} header
     * used in the {@link #getUpgradeRequest() upgrade request}.
     *
     * @return the WebSocket protocol version
     */
    String getProtocolVersion();

    /**
     * @return the UpgradeRequest used to create this session
     */
    UpgradeRequest getUpgradeRequest();

    /**
     * @return the UpgradeResponse used to create this session
     */
    UpgradeResponse getUpgradeResponse();

    /**
     * @return whether the session is open
     */
    boolean isOpen();

    /**
     * @return whether the underlying socket is using a secure transport
     */
    boolean isSecure();

    interface Listener
    {
        /**
         * <p>A WebSocket {@link Session} has connected successfully and is ready to be used.</p>
         * <p>Applications can store the given {@link Session} as a field so it can be used
         * to send messages back to the other peer.</p>
         *
         * @param session the WebSocket session
         */
        default void onWebSocketConnect(Session session)
        {
        }

        /**
         * <p>A WebSocket frame has been received.</p>
         * <p>The received frames may be control frames such as PING, PONG or CLOSE,
         * or data frames either BINARY or TEXT.</p>
         *
         * @param frame the received frame
         */
        default void onWebSocketFrame(Frame frame, Callback callback)
        {
            callback.succeed();
        }

        /**
         * <p>A WebSocket PING frame has been received.</p>
         *
         * @param payload the PING payload
         */
        default void onWebSocketPing(ByteBuffer payload)
        {
        }

        /**
         * <p>A WebSocket PONG frame has been received.</p>
         *
         * @param payload the PONG payload
         */
        default void onWebSocketPong(ByteBuffer payload)
        {
        }

        /**
         * <p>A WebSocket BINARY (or associated CONTINUATION) frame has been received.</p>
         * <p>The {@code ByteBuffer} is read-only, and will be recycled when the {@code callback}
         * is completed.</p>
         *
         * @param payload the BINARY frame payload
         * @param last whether this is the last frame
         * @param callback the callback to complete when the payload has been processed
         */
        default void onWebSocketPartialBinary(ByteBuffer payload, boolean last, Callback callback)
        {
            callback.succeed();
        }

        /**
         * <p>A WebSocket TEXT (or associated CONTINUATION) frame has been received.</p>
         *
         * @param payload the text message payload
         * <p>
         * Note that due to framing, there is a above average chance of any UTF8 sequences being split on the
         * border between two frames will result in either the previous frame, or the next frame having an
         * invalid UTF8 sequence, but the combined frames having a valid UTF8 sequence.
         * <p>
         * The String being provided here will not end in a split UTF8 sequence. Instead this partial sequence
         * will be held over until the next frame is received.
         * @param last whether this is the last frame
         */
        default void onWebSocketPartialText(String payload, boolean last)
        {
        }

        /**
         * <p>A WebSocket BINARY message has been received.</p>
         *
         * @param payload the raw payload array received
         */
        default void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            callback.succeed();
        }

        /**
         * <p>A WebSocket TEXT message has been received.</p>
         *
         * @param message the text payload
         */
        default void onWebSocketText(String message)
        {
        }

        /**
         * <p>A WebSocket error has occurred during the processing of WebSocket frames.</p>
         * <p>Usually errors occurs from bad or malformed incoming packets, for example
         * text frames that do not contain UTF-8 bytes, frames that are too big, or other
         * violations of the WebSocket specification.</p>
         * <p>The WebSocket {@link Session} will be closed, but applications may
         * explicitly {@link Session#close(int, String, Callback) close} the
         * {@link Session} providing a different status code or reason.</p>
         *
         * @param cause the error that occurred
         */
        default void onWebSocketError(Throwable cause)
        {
        }

        /**
         * <p>The WebSocket {@link Session} has been closed.</p>
         *
         * @param statusCode the close {@link StatusCode status code}
         * @param reason the optional reason for the close
         */
        default void onWebSocketClose(int statusCode, String reason)
        {
        }

        /**
         * <p>Tag interface that signals that the WebSocket endpoint
         * is demanding for WebSocket frames automatically.</p>
         *
         * @see WebSocket#autoDemand()
         */
        interface AutoDemanding
        {
        }

        abstract class Abstract implements Listener
        {
            private volatile Session session;

            @Override
            public void onWebSocketConnect(Session session)
            {
                this.session = session;
            }

            public Session getSession()
            {
                return session;
            }

            public boolean isConnected()
            {
                Session session = this.session;
                return session != null && session.isOpen();
            }
        }
    }
}
