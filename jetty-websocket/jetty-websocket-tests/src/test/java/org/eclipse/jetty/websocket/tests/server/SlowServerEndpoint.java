//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.server;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class SlowServerEndpoint
{
    private static final Logger LOG = Log.getLogger(SlowServerEndpoint.class);

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
                        LOG.warn(cause);
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
                LOG.ignore(ignore);
            }
        }
    }
}
