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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.io.IOException;
import java.nio.ByteBuffer;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/")
@ClientEndpoint
public class EchoSocket extends EventSocket
{
    @Override
    public void onMessage(String message) throws IOException
    {
        super.onMessage(message);
        session.getBasicRemote().sendText(message);
    }

    @Override
    public void onMessage(ByteBuffer message) throws IOException
    {
        super.onMessage(message);
        session.getBasicRemote().sendBinary(message);
    }
}
