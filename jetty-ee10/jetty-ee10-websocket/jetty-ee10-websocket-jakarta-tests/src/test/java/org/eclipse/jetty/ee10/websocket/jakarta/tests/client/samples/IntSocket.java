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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.client.samples;

import java.io.IOException;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.EncodeException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.coders.BadDualDecoder;

@ClientEndpoint(decoders = BadDualDecoder.class)
public class IntSocket
{
    @OnMessage
    public void onInt(Session session, int value)
    {
        try
        {
            session.getBasicRemote().sendObject(value);
        }
        catch (IOException | EncodeException e)
        {
            e.printStackTrace();
        }
    }
}
