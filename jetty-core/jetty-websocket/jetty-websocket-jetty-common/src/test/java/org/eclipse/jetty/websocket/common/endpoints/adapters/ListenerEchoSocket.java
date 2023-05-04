//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

/**
 * Example EchoSocket using Listener.
 */
public class ListenerEchoSocket implements Session.Listener
{
    private Session outbound;

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback)
    {
        // only interested in text messages.
        callback.succeed();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.outbound = null;
    }

    @Override
    public void onWebSocketOpen(Session session)
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
            outbound.sendText(message, null);
        }
    }
}
