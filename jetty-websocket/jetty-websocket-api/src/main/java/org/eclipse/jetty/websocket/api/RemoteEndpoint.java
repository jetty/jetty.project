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

package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;

public interface RemoteEndpoint
{
    /**
     * Send a binary message, returning when all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param data the message to be sent
     * @throws IOException if unable to send the bytes
     * @since 10.0
     */
    void sendBinary(ByteBuffer data) throws IOException;

    /**
     * Send a binary message in pieces, blocking until all of the message has been transmitted. The runtime reads the
     * message in order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @throws IOException if unable to send the partial bytes
     * @since 10.0
     */
    void sendPartialBinary(ByteBuffer fragment, boolean isLast) throws IOException;

    /**
     * Send a text message in pieces, blocking until all of the message has been transmitted. The runtime reads the
     * message in order. Non-final pieces are sent
     * with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @throws IOException if unable to send the partial bytes
     * @since 10.0
     */
    void sendPartialText(String fragment, boolean isLast) throws IOException;

    /**
     * Send a Ping message containing the given application data to the remote endpoint. The corresponding Pong message
     * may be picked up using the
     * MessageHandler.Pong handler.
     *
     * @param applicationData the data to be carried in the ping request
     * @throws IOException if unable to send the ping
     */
    void sendPing(ByteBuffer applicationData) throws IOException;

    /**
     * Allows the developer to send an unsolicited Pong message containing the given application data in order to serve
     * as a unidirectional heartbeat for the
     * session.
     *
     * @param applicationData the application data to be carried in the pong response.
     * @throws IOException if unable to send the pong
     */
    void sendPong(ByteBuffer applicationData) throws IOException;

    /**
     * Send a text message, blocking until all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param text the message to be sent
     * @throws IOException if unable to send the text message
     * @since 10.0
     */
    void sendText(String text) throws IOException;

    /**
     * Send a binary message, triggering the callback when bytes have been transmitted.
     * <p>
     * Note: this is an async call
     *
     * @param data the message to be sent
     * @param callback the callback for the send operation
     * @since 10.0
     */
    void sendBinary(ByteBuffer data, Callback callback);

    /**
     * Send a binary message in pieces, triggering the callback when the partial message has been transmitted.
     * <p>
     * The runtime reads the message in order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback the callback for the send operation
     * @since 10.0
     */
    void sendPartialBinary(ByteBuffer fragment, boolean isLast, Callback callback);

    /**
     * Send a text message in pieces, triggering the callback when the partial message has been transmitted.
     * <p>The runtime reads the message in order. Non-final pieces are sent
     * with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial text
     * @param callback the callback for the send operation
     * @since 10.0
     */
    void sendPartialText(String fragment, boolean isLast, Callback callback);

    /**
     * Initiates the asynchronous transmission of a text message. This method may return before the message is
     * transmitted. Developers may use the callback be notified that the message has been transmitted.
     *
     * @param text the text being sent
     * @param callback the callback for the send operation
     * @since 10.0
     */
    void sendText(String text, Callback callback);
}
