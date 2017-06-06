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

package org.eclipse.jetty.websocket.tests.servlets;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

/**
 * Example of a basic blocking echo socket.
 */
public class EchoSocket extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(EchoSocket.class);
    
    @Override
    public void onWebSocketText(String message)
    {
        if (isNotConnected())
        {
            return;
        }
        
        try
        {
            // echo the data back
            RemoteEndpoint remote = getRemote();
            remote.sendString(message);
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }
    
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (isNotConnected())
        {
            return;
        }
        
        try
        {
            // echo the data back
            RemoteEndpoint remote = getRemote();
            remote.sendBytes(ByteBuffer.wrap(payload, offset, len));
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }
    
    @Override
    public void onWebSocketError(Throwable cause)
    {
        LOG.warn(cause);
    }
}
