//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.io.BatchMode;

/**
 * The interface a WSConnection has to the remote WebSocket Endpoint.
 */
public interface WebSocketRemoteEndpoint
{
    /**
     * Get the batch mode behavior.
     *
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
     * Asynchronous send of Close frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param statusCode the close status code to send
     * @param reason the close reason (can be null)
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendClose(int statusCode, String reason, Callback callback);

    /**
     * Asynchronous send of a whole Binary message.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param data the data being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendBinary(ByteBuffer data, Callback callback);

    /**
     * Asynchronous send of a partial Binary message.
     * <p>
     * Be mindful of partial message order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     * </p>
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPartialBinary(ByteBuffer fragment, boolean isLast, Callback callback);

    /**
     * Asynchronous send of a partial Text message.
     * <p>
     * Be mindful of partial message order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     * </p>
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPartialText(String fragment, boolean isLast, Callback callback);

    /**
     * Asynchronous send of a whole Text message.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param text the text being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendText(String text, Callback callback);

    /**
     * Asynchronous send of a Ping frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param applicationData the data to be carried in the ping request
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPing(ByteBuffer applicationData, Callback callback);

    /**
     * Asynchronous send of a Pong frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param applicationData the application data to be carried in the pong response.
     * @param callback callback to notify of success or failure of the write operation
     */
    void sendPong(ByteBuffer applicationData, Callback callback);

    /**
     * Flushes messages that may have been batched by the implementation.
     *
     * @throws IOException if the flush fails
     * @see #getBatchMode()
     */
    public void flush() throws IOException;
}
