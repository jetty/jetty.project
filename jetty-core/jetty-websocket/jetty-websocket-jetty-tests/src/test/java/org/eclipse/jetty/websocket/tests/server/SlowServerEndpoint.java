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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

import org.eclipse.jetty.util.thread.ThreadIdCache;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket
public class SlowServerEndpoint
{
    private static final Logger LOG = LoggerFactory.getLogger(SlowServerEndpoint.class);

    @OnWebSocketMessage
    public void onMessage(Session session, String msg)
    {
        RandomGenerator random = ThreadIdCache.Random.instance();

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
                        session.sendText("Hello/" + i + "/", Callback.NOOP);
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
            session.sendText(msg, Callback.NOOP);
        }
    }
}
