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

package org.eclipse.jetty.websocket.core.examples.echo;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.api.WebSocketException;
import org.eclipse.jetty.websocket.core.api.WebSocketListener;

/**
 * Example EchoSocket using Listener.
 */
public class ListenerEchoSocket implements WebSocketListener
{
    private static final Logger LOG = Logger.getLogger(ListenerEchoSocket.class.getName());
    private WebSocketConnection outbound;

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        /* only interested in text messages */
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.outbound = null;
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        this.outbound = connection;
    }

    @Override
    public void onWebSocketException(WebSocketException error)
    {
        LOG.log(Level.WARNING,"onWebSocketException",error);
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (outbound == null)
        {
            return;
        }

        try
        {
            String context = null;
            Callback<String> callback = new FutureCallback<>();
            outbound.write(context,callback,message);
        }
        catch (IOException e)
        {
            LOG.log(Level.WARNING,"unable to echo message: " + message,e);
        }
    }
}
