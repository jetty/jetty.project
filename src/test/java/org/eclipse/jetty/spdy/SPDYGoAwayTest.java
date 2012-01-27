package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SPDYGoAwayTest extends SPDYTest
{
    @Test
    public void testGoAwayOnClientClose() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                Assert.assertEquals(0, goAwayInfo.getLastStreamId());
                Assert.assertSame(SessionStatus.OK, goAwayInfo.getSessionStatus());
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), null);

        short version = (short)2;
        session.syn(version, new SynInfo(true), null);

        session.goAway(version);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGoAwayOnServerClose() throws Exception
    {
        final short version = (short)2;
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                stream.getSession().goAway(version);
                return null;
            }
        };
        final AtomicReference<GoAwayInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                ref.set(goAwayInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Stream stream1 = session.syn(version, new SynInfo(true), null);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        GoAwayInfo goAwayInfo = ref.get();
        Assert.assertNotNull(goAwayInfo);
        Assert.assertEquals(stream1.getId(), goAwayInfo.getLastStreamId());
        Assert.assertSame(SessionStatus.OK, goAwayInfo.getSessionStatus());
    }

    @Test
    public void testSynStreamIgnoredAfterGoAway() throws Exception
    {
        final short version = (short)2;
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            private final AtomicInteger syns = new AtomicInteger();

            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                int synCount = syns.incrementAndGet();
                if (synCount == 1)
                {
                    stream.reply(new ReplyInfo(true));
                    stream.getSession().goAway(version);
                }
                else
                {
                    latch.countDown();
                }
                return null;
            }
        };
        final AtomicReference<Session> ref = new AtomicReference<>();
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                ref.get().syn(version, new SynInfo(true), null);
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);
        ref.set(session);

        session.syn(version, new SynInfo(true), null);

        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testDataNotProcessedAfterGoAway() throws Exception
    {
        final short version = (short)2;
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            private AtomicInteger syns = new AtomicInteger();

            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                int synCount = syns.incrementAndGet();
                if (synCount == 1)
                {
                    return null;
                }
                else
                {
                    stream.getSession().goAway(version);
                    closeLatch.countDown();
                    return new Stream.FrameListener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            dataLatch.countDown();
                        }
                    };
                }
            }
        };
        final AtomicReference<GoAwayInfo> goAwayRef = new AtomicReference<>();
        final CountDownLatch goAwayLatch = new CountDownLatch(1);
        Session.FrameListener clientSessionFrameListener = new Session.FrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                goAwayRef.set(goAwayInfo);
                goAwayLatch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        // First stream is processed ok
        final CountDownLatch reply1Latch = new CountDownLatch(1);
        Stream stream1 = session.syn(version, new SynInfo(true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                reply1Latch.countDown();
            }
        });
        Assert.assertTrue(reply1Latch.await(5, TimeUnit.SECONDS));

        // Second stream is closed in the middle
        Stream stream2 = session.syn(version, new SynInfo(false), null);
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        stream2.data(new StringDataInfo("foo", true));
        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        // Be sure the last good stream is the first
        Assert.assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
        GoAwayInfo goAway = goAwayRef.get();
        Assert.assertNotNull(goAway);
        Assert.assertEquals(stream1.getId(), goAway.getLastStreamId());
    }
}
