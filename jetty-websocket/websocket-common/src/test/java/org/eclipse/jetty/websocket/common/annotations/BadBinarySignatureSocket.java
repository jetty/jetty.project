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

package org.eclipse.jetty.websocket.common.annotations;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Invalid Socket: Annotate a message interest on a method with a return type.
 */
@WebSocket
public class BadBinarySignatureSocket
{
    /**
     * Declaring a non-void return type
     * @param session  the session
     * @param buf the buffer
     * @param offset the offset
     * @param len the length
     * @return the response boolean
     */
    @OnWebSocketMessage
    public boolean onBinary(Session session, byte buf[], int offset, int len)
    {
        return false;
    }
}
