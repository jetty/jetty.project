//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.api.exceptions;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketListener;
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
