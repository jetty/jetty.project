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

package org.eclipse.jetty.websocket.api;

import org.eclipse.jetty.websocket.api.io.WebSocketBlockingConnection;

/**
 * Default implementation of the {@link WebSocketListener}.
 * <p>
 * Convenient abstract class to base standard WebSocket implementations off of.
 */
public class WebSocketAdapter implements WebSocketListener
{
    private WebSocketConnection connection;
    private WebSocketBlockingConnection blocking;

    public WebSocketBlockingConnection getBlockingConnection()
    {
        return blocking;
    }

    public WebSocketConnection getConnection()
    {
        return connection;
    }

    public boolean isConnected()
    {
        return (connection != null) && (connection.isOpen());
    }

    public boolean isNotConnected()
    {
        return (connection == null) || (!connection.isOpen());
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.connection = null;
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        this.connection = connection;
        this.blocking = new WebSocketBlockingConnection(this.connection);
    }

    @Override
    public void onWebSocketException(WebSocketException error)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketText(String message)
    {
        /* do nothing */
    }
}
