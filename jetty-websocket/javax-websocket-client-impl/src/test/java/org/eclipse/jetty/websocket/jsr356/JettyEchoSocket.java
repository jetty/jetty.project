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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

/**
 * Jetty Echo Socket. using Jetty techniques.
 */
public class JettyEchoSocket extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(JettyEchoSocket.class);

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (isNotConnected())
            return;

        try
        {
            RemoteEndpoint remote = getRemote();
            remote.sendBytes(BufferUtil.toBuffer(payload, offset, len), null);
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        LOG.warn(cause);
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (isNotConnected())
            return;

        try
        {
            RemoteEndpoint remote = getRemote();
            remote.sendString(message, null);
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        super.onWebSocketClose(statusCode, reason);
        if (statusCode != StatusCode.NORMAL)
        {
            LOG.warn("Closed {} {}", statusCode, reason);
        }
    }
}
