/*******************************************************************************
 * Copyright (c) 2011 Mort Bay Consulting Pty. Ltd.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/

package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * WebSocket Interface.
 * <p>
 * This interface provides the signature for a server-side end point of a websocket connection.
 * The Interface has several nested interfaces, for each type of message that may be received.
 */
public interface WebSocket
{
    /**
     * A Connection interface is passed to a WebSocket instance via the {@link WebSocket#onOpen(Connection)} to give the application access to the specifics of
     * the current connection. This includes methods for sending frames and messages as well as methods for interpreting the flags and opcodes of the
     * connection.
     */
    public interface Connection
    {
        /**
         * Close the connection with normal close code.
         */
        void close();

        /** Close the connection with specific closeCode and message.
         * @param closeCode The close code to send, or -1 for no close code
         * @param message The message to send or null for no message
         */
        void close(int closeCode,String message);

        /**
         * Enqueue a text message to be sent.
         * <p>
         * It is possible that the message might be split up and fragmented to satisfy
         * policies in place on the websocket.
         * 
         * @param message the message text to send.
         * @throws IOException
         */
        void enqueTextMessage(String message) throws IOException;

        /**
         * Get the websocket policy in use for this connection.
         * @return connection specific websocket policy.
         */
        WebSocketPolicy getPolicy();

        /**
         * Get the active scheme. "ws" (websocket) or "wss" (websocket secure)
         * 
         * @return the active protocol scheme
         */
        String getProtocol();

        boolean isOpen();
    }

    /**
     * A nested WebSocket interface for receiving binary messages
     */
    interface OnBinaryMessage extends WebSocket
    {
        /**
         * Called with a complete binary message when all fragments have been received.
         * The maximum size of binary message that may be aggregated from multiple frames is set with {@link Connection#setMaxBinaryMessageSize(int)}.
         * @param data
         * @param offset
         * @param length
         */
        void onMessage(byte[] data, int offset, int length);
    }

    /**
     * A nested WebSocket interface for receiving control messages
     */
    interface OnControl extends WebSocket
    {
        /**
         * Called when a control message has been received.
         * @param controlCode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the control message and no further processing is needed.
         */
        boolean onControl(byte controlCode,byte[] data, int offset, int length);
    }

    /**
     * A nested WebSocket interface for receiving any websocket frame
     */
    interface OnFrame extends WebSocket
    {
        /**
         * Called when any websocket frame is received.
         * @param flags
         * @param opcode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the frame and no further processing is needed (including aggregation and/or message delivery)
         */
        boolean onFrame(byte flags,byte opcode,byte[] data, int offset, int length);

        void onHandshake(FrameConnection connection);
    }

    /**
     * A nested WebSocket interface for receiving text messages
     */
    interface OnTextMessage extends WebSocket
    {
        /**
         * Called with a complete text message when all fragments have been received.
         * The maximum size of text message that may be aggregated from multiple frames is set with {@link Connection#setMaxTextMessageSize(int)}.
         * @param data The message
         */
        void onMessage(String data);
    }

    /**
     * Called when an established websocket connection closes
     * @param closeCode
     * @param message
     */
    void onClose(int closeCode, String message);

    /**
     * Called when a new websocket connection is accepted.
     * @param connection The Connection object to use to send messages.
     */
    void onOpen(Connection connection);
}
