//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public interface RemoteEndpoint
{
    /**
     * Send a binary message, returning when all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param data the message to be sent
     * @throws IOException if unable to send the bytes
     */
    void sendBytes(ByteBuffer data) throws IOException;

    /**
     * Initiates the asynchronous transmission of a binary message. This method returns before the message is transmitted.
     * Developers may provide a callback to be notified when the message has been transmitted or resulted in an error.
     *
     * @param data the data being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendBytes(ByteBuffer data, WriteCallback callback);

    /**
     * Send a binary message in pieces, blocking until all of the message has been transmitted.
     * The runtime reads the message in order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @throws IOException if unable to send the partial bytes
     */
    void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException;

    /**
     * Initiates the asynchronous transmission of a partial binary message. This method returns before the message is
     * transmitted.
     * The runtime reads the message in order. Non-final pieces are sent with isLast
     * set to false. The final piece must be sent with isLast set to true.
     * Developers may provide a callback to be notified when the message has been transmitted or resulted in an error.
     *
     * @param fragment the data being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPartialBytes(ByteBuffer fragment, boolean isLast, WriteCallback callback);

    /**
     * Send a text message, blocking until all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param text the message to be sent
     * @throws IOException if unable to send the text message
     */
    void sendString(String text) throws IOException;

    /**
     * Initiates the asynchronous transmission of a text message. This method may return before the message is
     * transmitted. Developers may provide a callback to
     * be notified when the message has been transmitted or resulted in an error.
     *
     * @param text the text being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendString(String text, WriteCallback callback);

    /**
     * Send a text message in pieces, blocking until all of the message has been transmitted. The runtime reads the
     * message in order. Non-final pieces are sent
     * with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @throws IOException if unable to send the partial bytes
     */
    void sendPartialString(String fragment, boolean isLast) throws IOException;

    /**
     * Initiates the asynchronous transmission of a partial text message.
     * This method may return before the message is transmitted.
     * The runtime reads the message in order. Non-final pieces are sent with isLast
     * set to false. The final piece must be sent with isLast set to true.
     * Developers may provide a callback to be notified when the message has been transmitted or resulted in an error.
     *
     * @param fragment the text being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPartialString(String fragment, boolean isLast, WriteCallback callback) throws IOException;

    /**
     * Send a Ping message containing the given application data to the remote endpoint, blocking until all of the
     * message has been transmitted.
     * The corresponding Pong message may be picked up using the MessageHandler.Pong handler.
     *
     * @param applicationData the data to be carried in the ping request
     * @throws IOException if unable to send the ping
     */
    void sendPing(ByteBuffer applicationData) throws IOException;

    /**
     * Asynchronously send a Ping message containing the given application data to the remote endpoint.
     * The corresponding Pong message may be picked up using the MessageHandler.Pong handler.
     *
     * @param applicationData the data to be carried in the ping request
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPing(ByteBuffer applicationData, WriteCallback callback);

    /**
     * Allows the developer to send an unsolicited Pong message containing the given application data
     * in order to serve as a unidirectional heartbeat for the session, this will block until
     * all of the message has been transmitted.
     *
     * @param applicationData the application data to be carried in the pong response.
     * @throws IOException if unable to send the pong
     */
    void sendPong(ByteBuffer applicationData) throws IOException;

    /**
     * Allows the developer to asynchronously send an unsolicited Pong message containing the given application data
     * in order to serve as a unidirectional heartbeat for the session.
     *
     * @param applicationData the application data to be carried in the pong response.
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPong(ByteBuffer applicationData, WriteCallback callback);

    /**
     * @return the batch mode with which messages are sent.
     * @see #flush()
     */
    BatchMode getBatchMode();

    /**
     * Set the batch mode with which messages are sent.
     *
     * @param mode the batch mode to use
     * @see #flush()
     */
    void setBatchMode(BatchMode mode);

    /**
     * Get the SocketAddress for the established connection.
     *
     * @return the SocketAddress for the established connection.
     */
    SocketAddress getRemoteAddress();

    /**
     * Flushes messages that may have been batched by the implementation.
     *
     * @throws IOException if the flush fails
     */
    void flush() throws IOException;
}
