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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;

public interface RemoteEndpoint extends WebSocketRemoteEndpoint
{
    /**
     * Send a binary message, returning when all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param data the message to be sent
     * @throws IOException if unable to send the bytes
     * @see #sendBinary(ByteBuffer, Callback)
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
     * @see #sendPartialBinary(ByteBuffer, boolean, Callback)
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
     * @see #sendPartialText(String, boolean, Callback)
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
     * @see #sendPing(ByteBuffer, Callback)
     */
    void sendPing(ByteBuffer applicationData) throws IOException;

    /**
     * Allows the developer to send an unsolicited Pong message containing the given application data in order to serve
     * as a unidirectional heartbeat for the
     * session.
     *
     * @param applicationData the application data to be carried in the pong response.
     * @throws IOException if unable to send the pong
     * @see #sendPong(ByteBuffer, Callback)
     */
    void sendPong(ByteBuffer applicationData) throws IOException;

    /**
     * Send a text message, blocking until all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param text the message to be sent
     * @throws IOException if unable to send the text message
     * @see #sendText(String, Callback)
     * @since 10.0
     */
    void sendText(String text) throws IOException;

    /**
     * Send a binary message, returning when all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param data
     *            the message to be sent
     * @throws IOException
     *             if unable to send the bytes
     * @deprecated use {@link #sendBinary(ByteBuffer)}
     */
    @Deprecated
    void sendBytes(ByteBuffer data) throws IOException;

    /**
     * Initiates the asynchronous transmission of a binary message. This method returns before the message is
     * transmitted. Developers may use the returned
     * Future object to track progress of the transmission.
     *
     * @param data
     *            the data being sent
     * @return the Future object representing the send operation.
     * @deprecated use {@link #sendBinary(ByteBuffer, Callback)}
     */
    @Deprecated
    Future<Void> sendBytesByFuture(ByteBuffer data);

    /**
     * Initiates the asynchronous transmission of a binary message. This method returns before the message is
     * transmitted. Developers may provide a callback to
     * be notified when the message has been transmitted or resulted in an error.
     *
     * @param data
     *            the data being sent
     * @param callback
     *            callback to notify of success or failure of the write operation
     * @deprecated use {@link #sendBinary(ByteBuffer, Callback)}
     */
    @Deprecated
    void sendBytes(ByteBuffer data, WriteCallback callback);

    /**
     * Send a binary message in pieces, blocking until all of the message has been transmitted. The runtime reads the
     * message in order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment
     *            the piece of the message being sent
     * @param isLast
     *            true if this is the last piece of the partial bytes
     * @throws IOException
     *             if unable to send the partial bytes
     * @deprecated use {@link #sendPartialBinary(ByteBuffer, boolean, Callback)}
     */
    @Deprecated
    void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException;

    /**
     * Send a text message in pieces, blocking until all of the message has been transmitted. The runtime reads the
     * message in order. Non-final pieces are sent
     * with isLast set to false. The final piece must be sent with isLast set to true.
     *
     * @param fragment
     *            the piece of the message being sent
     * @param isLast
     *            true if this is the last piece of the partial bytes
     * @throws IOException
     *             if unable to send the partial bytes
     * @deprecated use {@link #sendPartialText(String, boolean, Callback)}
     */
    @Deprecated
    void sendPartialString(String fragment, boolean isLast) throws IOException;

    /**
     * Send a text message, blocking until all bytes of the message has been transmitted.
     * <p>
     * Note: this is a blocking call
     *
     * @param text
     *            the message to be sent
     * @throws IOException
     *             if unable to send the text message
     * @deprecated Use {@link #sendText(String)}
     */
    @Deprecated
    void sendString(String text) throws IOException;

    /**
     * Initiates the asynchronous transmission of a text message. This method may return before the message is
     * transmitted. Developers may use the returned
     * Future object to track progress of the transmission.
     *
     * @param text
     *            the text being sent
     * @return the Future object representing the send operation.
     * @deprecated Use {@link #sendText(String, Callback)}
     */
    @Deprecated
    Future<Void> sendStringByFuture(String text);

    /**
     * Initiates the asynchronous transmission of a text message. This method may return before the message is
     * transmitted. Developers may provide a callback to
     * be notified when the message has been transmitted or resulted in an error.
     *
     * @param text
     *            the text being sent
     * @param callback
     *            callback to notify of success or failure of the write operation
     * @deprecated Use {@link #sendText(String, Callback)}
     */
    @Deprecated
    void sendString(String text, WriteCallback callback);

    /**
     * Get the InetSocketAddress for the established connection.
     *
     * @return the InetSocketAddress for the established connection. (or null, if the connection is no longer established)
     * @deprecated use {@link Session#getRemoteAddress()}
     */
    @Deprecated
    InetSocketAddress getInetSocketAddress();
}
