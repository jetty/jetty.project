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

package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

/**
 * Example of a basic blocking echo socket.
 */
public class MyEchoSocket extends WebSocketAdapter
{
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
}
