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

package org.eclipse.jetty.ee10.websocket.tests.server;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket
public class SlowServerEndpoint
{
    private static final Logger LOG = LoggerFactory.getLogger(SlowServerEndpoint.class);

    @OnWebSocketMessage
    public void onMessage(Session session, String msg)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (msg.startsWith("send-slow|"))
        {
            int idx = msg.indexOf('|');
            int msgCount = Integer.parseInt(msg.substring(idx + 1));
            CompletableFuture.runAsync(() ->
            {
                for (int i = 0; i < msgCount; i++)
                {
                    try
                    {
                        session.getRemote().sendString("Hello/" + i + "/");
                        // fake some slowness
                        TimeUnit.MILLISECONDS.sleep(random.nextInt(2000));
                    }
                    catch (Throwable cause)
                    {
                        LOG.warn("failed to send text", cause);
                    }
                }
            });
        }
        else
        {
            // echo message.
            try
            {
                session.getRemote().sendString(msg);
            }
            catch (IOException ignore)
            {
                LOG.trace("IGNORED", ignore);
            }
        }
    }
}
