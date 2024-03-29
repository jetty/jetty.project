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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.handlers;

import java.io.IOException;
import java.nio.ByteBuffer;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/")
public class AbstractAnnotatedHandler
{
    protected Session _session;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        _session = session;
    }

    @OnError
    public void onError(Session session, Throwable thr)
    {
        thr.printStackTrace();
    }

    public void sendText(String message, boolean last)
    {
        try
        {
            _session.getBasicRemote().sendText(message, last);
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
            _session.getBasicRemote().sendBinary(message, last);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
