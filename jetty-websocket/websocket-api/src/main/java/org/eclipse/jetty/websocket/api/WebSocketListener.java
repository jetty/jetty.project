//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

/**
 * Basic WebSocket Listener interface for incoming WebSocket events.
 */
public interface WebSocketListener
{
    /**
     * A WebSocket binary frame has been received.
     * 
     * @param payload
     *            the raw payload array received
     * @param offset
     *            the offset in the payload array where the data starts
     * @param len
     *            the length of bytes in the payload
     */
    void onWebSocketBinary(byte payload[], int offset, int len);

    /**
     * A Close Event was received.
     * <p>
     * The underlying {@link WebSocketConnection} will be considered closed at this point.
     * 
     * @param statusCode
     *            the close status code. (See {@link StatusCode})
     * @param reason
     *            the optional reason for the close.
     */
    void onWebSocketClose(int statusCode, String reason);

    /**
     * A WebSocketConnection has connected successfully and is ready to be used.
     * <p>
     * Note: It is a good idea to track this connection as a field in your object so that you can write messages back.
     * 
     * @param connection
     *            the connection to use to send messages on.
     */
    void onWebSocketConnect(WebSocketConnection connection);

    /**
     * A WebSocket exception has occurred.
     * <p>
     * Usually this occurs from bad / malformed incoming packets. (example: bad UTF8 data, frames that are too big, violations of the spec)
     * <p>
     * This will result in the {@link WebSocketConnection} being closed by the implementing side.
     * <p>
     * Note: you will receive no {@link #onWebSocketClose(int, String)} as this condition results in the API calling
     * {@link WebSocketConnection#close(int, String)} for you.
     * 
     * @param error
     *            the error that occurred.
     */
    void onWebSocketException(WebSocketException error);

    /**
     * A WebSocket Text frame was received.
     * 
     * @param message
     */
    void onWebSocketText(String message);
}
