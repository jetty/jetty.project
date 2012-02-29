/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
