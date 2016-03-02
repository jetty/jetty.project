//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * Basic WebSocket Listener interface for incoming WebSocket message events.
 */
public interface WebSocketListener extends WebSocketConnectionListener
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
     * A WebSocket Text frame was received.
     * 
     * @param message the message
     */
    void onWebSocketText(String message);
}
