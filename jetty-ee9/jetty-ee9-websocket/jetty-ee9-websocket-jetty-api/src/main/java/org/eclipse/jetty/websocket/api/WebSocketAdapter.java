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

package org.eclipse.jetty.websocket.api;

/**
 * Default implementation of the {@link WebSocketListener}.
 * <p>
 * Convenient abstract class to base standard WebSocket implementations off of.
 */
public class WebSocketAdapter implements WebSocketListener
{
    private volatile Session session;
    private RemoteEndpoint remote;

    public RemoteEndpoint getRemote()
    {
        return remote;
    }

    public Session getSession()
    {
        return session;
    }

    public boolean isConnected()
    {
        Session sess = this.session;
        return (sess != null) && (sess.isOpen());
    }

    public boolean isNotConnected()
    {
        return !isConnected();
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketConnect(Session sess)
    {
        this.session = sess;
        this.remote = sess.getRemote();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        /* do nothing */
    }

    @Override
    public void onWebSocketText(String message)
    {
        /* do nothing */
    }
}
