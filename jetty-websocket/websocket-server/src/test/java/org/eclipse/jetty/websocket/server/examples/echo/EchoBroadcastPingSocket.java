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

package org.eclipse.jetty.websocket.server.examples.echo;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class EchoBroadcastPingSocket extends EchoBroadcastSocket
{
    private static class KeepAlive extends Thread
    {
        private CountDownLatch latch;
        private Session session;

        public KeepAlive(Session session)
        {
            this.session = session;
        }

        @Override
        public void run()
        {
            try
            {
                while (!latch.await(10, TimeUnit.SECONDS))
                {
                    System.err.println("Ping");
                    ByteBuffer data = ByteBuffer.allocate(3);
                    data.put(new byte[]
                        {(byte)1, (byte)2, (byte)3});
                    data.flip();
                    session.getRemote().sendPing(data);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        public void shutdown()
        {
            if (latch != null)
            {
                latch.countDown();
            }
        }

        @Override
        public synchronized void start()
        {
            latch = new CountDownLatch(1);
            super.start();
        }
    }

    private KeepAlive keepAlive; // A dedicated thread is not a good way to do this

    public EchoBroadcastPingSocket()
    {
    }

    @Override
    public void onClose(int statusCode, String reason)
    {
        keepAlive.shutdown();
        super.onClose(statusCode, reason);
    }

    @Override
    public void onOpen(Session session)
    {
        if (keepAlive == null)
        {
            keepAlive = new KeepAlive(session);
        }
        keepAlive.start();
        super.onOpen(session);
    }
}
