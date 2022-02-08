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

package org.eclipse.jetty.websocket.javax.tests.server.examples;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.IO;

@ServerEndpoint("/echo")
public class StreamingEchoSocket
{
    @OnMessage
    public void onMessage(Session session, Reader reader)
    {
        try (Writer writer = session.getBasicRemote().getSendWriter())
        {
            IO.copy(reader, writer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
