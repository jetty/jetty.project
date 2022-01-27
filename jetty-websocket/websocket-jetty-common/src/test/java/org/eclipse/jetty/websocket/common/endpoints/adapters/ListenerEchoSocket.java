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

package org.eclipse.jetty.websocket.common.endpoints.adapters;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;

/**
 * Example EchoSocket using Listener.
 */
public class ListenerEchoSocket implements WebSocketListener
{
    private Session outbound;

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
    public void onWebSocketConnect(Session session)
    {
        this.outbound = session;
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        cause.printStackTrace(System.err);
    }

    @Override
    public void onWebSocketText(String message)
    {
        if ((outbound != null) && (outbound.isOpen()))
        {
            System.out.printf("Echoing back message [%s]%n", message);
            // echo the message back
            outbound.getRemote().sendString(message, null);
        }
    }
}
