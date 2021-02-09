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

package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;

/**
 * Example of a blocking echo websocket.
 */
public class BasicEchoSocket extends WebSocketAdapter
{
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (isNotConnected())
        {
            return;
        }
        try
        {
            ByteBuffer buf = ByteBuffer.wrap(payload, offset, len);
            getRemote().sendBytes(buf);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (isNotConnected())
        {
            return;
        }
        try
        {
            getRemote().sendString(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
