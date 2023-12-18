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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;

public class BadEndpoint
{
    public void onOpen(jakarta.websocket.Session session, EndpointConfig config)
    {
        try
        {
            session.addMessageHandler((MessageHandler.Whole<Object>)System.out::println);
            System.out.println("server open");
            session.getBasicRemote().sendText("connected");
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }
}
