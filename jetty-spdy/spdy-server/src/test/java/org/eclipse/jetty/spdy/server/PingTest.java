//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.PingResultInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class PingTest extends AbstractTest
{
    @Test
    public void testPingPong() throws Exception
    {
        final AtomicReference<PingResultInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onPing(Session session, PingResultInfo pingInfo)
            {
                ref.set(pingInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(null), clientSessionFrameListener);
        PingResultInfo pingResultInfo = session.ping(new PingInfo(5, TimeUnit.SECONDS));
        Assert.assertEquals(1, pingResultInfo.getPingId() % 2);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        PingResultInfo pongInfo = ref.get();
        Assert.assertNotNull(pongInfo);
        Assert.assertEquals(pingResultInfo.getPingId(), pongInfo.getPingId());
    }

    @Test
    public void testServerPingPong() throws Exception
    {
        final CountDownLatch pingReceived = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            private final CountDownLatch pingSent = new CountDownLatch(1);
            private int pingId;

            @Override
            public void onConnect(Session session)
            {
                session.ping(new PingInfo(), new Promise.Adapter<PingResultInfo>()
                {
                    @Override
                    public void succeeded(PingResultInfo pingInfo)
                    {
                        pingId = pingInfo.getPingId();
                        pingSent.countDown();
                    }
                });
            }

            @Override
            public void onPing(Session session, PingResultInfo pingInfo)
            {
                try
                {
                    // This callback may be notified before the promise above,
                    // so make sure we wait here to know the pingId
                    Assert.assertTrue(pingSent.await(5, TimeUnit.SECONDS));
                    Assert.assertEquals(0, pingInfo.getPingId() % 2);
                    Assert.assertEquals(pingId, pingInfo.getPingId());
                    pingReceived.countDown();
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                }
            }
        };
        startClient(startServer(serverSessionFrameListener), null);

        Assert.assertTrue(pingReceived.await(5, TimeUnit.SECONDS));
    }
}
