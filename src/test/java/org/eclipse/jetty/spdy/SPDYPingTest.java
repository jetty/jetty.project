package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SPDYPingTest extends SPDYTest
{
    @Test
    public void testPingPong() throws Exception
    {
        final AtomicReference<PingInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public void onPing(Session session, PingInfo pingInfo)
            {
                ref.set(pingInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(null), clientSessionFrameListener);
        PingInfo pingInfo = session.ping((short)2);
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
                PingInfo pingInfo = session.ping((short)2);
                this.pingId = pingInfo.getPingId();
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
