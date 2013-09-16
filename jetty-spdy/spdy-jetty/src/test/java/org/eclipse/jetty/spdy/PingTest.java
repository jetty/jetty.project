//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class PingTest extends AbstractTest
{
    @Test
    public void testPingPong() throws Exception
    {
        final AtomicReference<PingInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onPing(Session session, PingInfo pingInfo)
            {
                ref.set(pingInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(null), clientSessionFrameListener);
        PingInfo pingInfo = session.ping().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(1, pingInfo.getPingId() % 2);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        PingInfo pongInfo = ref.get();
        Assert.assertNotNull(pongInfo);
        Assert.assertEquals(pingInfo.getPingId(), pongInfo.getPingId());
    }

    @Test
    public void testServerPingPong() throws Exception
    {
        final CountDownLatch pingLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            public volatile int pingId;

            @Override
            public void onConnect(Session session)
            {
                session.ping(0, TimeUnit.MILLISECONDS, new Handler.Adapter<PingInfo>()
                {
                    @Override
                    public void completed(PingInfo pingInfo)
                    {
                        pingId = pingInfo.getPingId();
                    }
                });
            }

            @Override
            public void onPing(Session session, PingInfo pingInfo)
            {
                Assert.assertEquals(0, pingInfo.getPingId() % 2);
                Assert.assertEquals(pingId, pingInfo.getPingId());
                pingLatch.countDown();
            }
        };
        startClient(startServer(serverSessionFrameListener), null);

        Assert.assertTrue(pingLatch.await(5, TimeUnit.SECONDS));
    }
}
