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

package org.eclipse.jetty.websocket.common.annotations;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Test of constructing a new WebSocket based on a base class
 */
@WebSocket
public class MyEchoBinarySocket extends MyEchoSocket
{
    @OnWebSocketMessage
    public void echoBin(byte[] buf, int offset, int length)
    {
        try
        {
            getRemote().sendBytes(ByteBuffer.wrap(buf, offset, length));
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
