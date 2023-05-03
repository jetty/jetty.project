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

package org.eclipse.jetty.websocket.tests.listeners;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class AbstractAnnotatedListener
{
    protected Session _session;

    @OnWebSocketOpen
    public void onWebSocketOpen(Session session)
    {
        _session = session;
    }

    @OnWebSocketError
    public void onWebSocketError(Throwable thr)
    {
        thr.printStackTrace();
    }

    public void sendText(String message, boolean last)
    {
        _session.sendPartialText(message, last, Callback.NOOP);
    }

    public void sendBinary(ByteBuffer message, boolean last)
    {
        _session.sendPartialBinary(message, last, Callback.NOOP);
    }
}
