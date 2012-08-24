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

package org.eclipse.jetty.websocket.annotations;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Invalid Socket: Annotate a message interest on a static method
 */
@WebSocket
public class BadTextSignatureSocket
{
    /**
     * Declaring a static method
     */
    @OnWebSocketMessage
    public static void onText(WebSocketConnection conn, String text)
    {
        /* do nothing */
    }
}
