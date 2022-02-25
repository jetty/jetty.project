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

package org.eclipse.jetty.websocket.tests.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.tests.util.FutureWriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientWriteThread extends Thread
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientWriteThread.class);
    private final Session session;
    private int slowness = -1;
    private int messageCount = 100;
    private String message = "Hello";

    public ClientWriteThread(Session session)
    {
        this.session = session;
    }

    public String getMessage()
    {
        return message;
    }

    public int getMessageCount()
    {
        return messageCount;
    }

    public int getSlowness()
    {
        return slowness;
    }

    @Override
    public void run()
    {
        final AtomicInteger m = new AtomicInteger();

        try
        {
            LOG.debug("Writing {} messages to {}", messageCount, session);
            LOG.debug("Artificial Slowness {} ms", slowness);
            FutureWriteCallback lastMessage = null;
            RemoteEndpoint remote = session.getRemote();
            while (m.get() < messageCount)
            {
                lastMessage = new FutureWriteCallback();
                remote.sendString(message + "/" + m.get() + "/", lastMessage);

                m.incrementAndGet();

                if (slowness > 0)
                {
                    TimeUnit.MILLISECONDS.sleep(slowness);
                }
            }
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
            // block on write of last message
            if (lastMessage != null)
                lastMessage.get(2, TimeUnit.MINUTES); // block on write
        }
        catch (Exception e)
        {
            LOG.warn("Unable to write messages", e);
        }
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public void setMessageCount(int messageCount)
    {
        this.messageCount = messageCount;
    }

    public void setSlowness(int slowness)
    {
        this.slowness = slowness;
    }
}
