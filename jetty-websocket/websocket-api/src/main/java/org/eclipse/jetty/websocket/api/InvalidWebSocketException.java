//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Indicating that the provided Class is not a valid WebSocket as defined by the API.
 * <p>
 * A valid WebSocket should do one of the following:
 * <ul>
 * <li>Implement {@link WebSocketListener}</li>
 * <li>Extend {@link WebSocketAdapter}</li>
 * <li>Declare the {@link WebSocket &#064;WebSocket} annotation on the type</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class InvalidWebSocketException extends WebSocketException
{
    public InvalidWebSocketException(String message)
    {
        super(message);
    }

    public InvalidWebSocketException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
