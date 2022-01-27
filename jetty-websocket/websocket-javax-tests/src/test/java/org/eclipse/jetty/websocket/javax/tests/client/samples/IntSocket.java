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

package org.eclipse.jetty.websocket.javax.tests.client.samples;

import java.io.IOException;
import javax.websocket.ClientEndpoint;
import javax.websocket.EncodeException;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.javax.tests.coders.BadDualDecoder;

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
