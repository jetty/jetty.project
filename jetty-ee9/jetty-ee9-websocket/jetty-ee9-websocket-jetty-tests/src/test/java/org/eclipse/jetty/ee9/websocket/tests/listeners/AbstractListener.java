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

package org.eclipse.jetty.ee9.websocket.tests.listeners;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketConnectionListener;

public class AbstractListener implements WebSocketConnectionListener
{
    protected Session _session;

    @Override
    public void onWebSocketConnect(Session session)
    {
        _session = session;
    }

    @Override
    public void onWebSocketError(Throwable thr)
    {
        thr.printStackTrace();
    }

    public void sendText(String message, boolean last)
    {
        try
        {
            _session.getRemote().sendPartialString(message, last);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void sendBinary(ByteBuffer message, boolean last)
    {
        try
        {
            _session.getRemote().sendPartialBytes(message, last);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
