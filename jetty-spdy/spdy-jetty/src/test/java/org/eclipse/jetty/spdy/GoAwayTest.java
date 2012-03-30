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

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;

public class GoAwayTest extends AbstractTest
{
    @Test
    public void testServerReceivesGoAwayOnClientGoAway() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
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

        session.syn(new SynInfo(true), null);

        session.goAway();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientReceivesGoAwayOnServerGoAway() throws Exception
    {
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                stream.getSession().goAway();
                return null;
            }
        };
        final AtomicReference<GoAwayInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                ref.set(goAwayInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Stream stream1 = session.syn(new SynInfo(true), null).get(5, TimeUnit.SECONDS);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        GoAwayInfo goAwayInfo = ref.get();
        Assert.assertNotNull(goAwayInfo);
        Assert.assertEquals(stream1.getId(), goAwayInfo.getLastStreamId());
        Assert.assertSame(SessionStatus.OK, goAwayInfo.getSessionStatus());
    }

    @Test
    public void testSynStreamIgnoredAfterGoAway() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            private final AtomicInteger syns = new AtomicInteger();

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                int synCount = syns.incrementAndGet();
                if (synCount == 1)
                {
                    stream.reply(new ReplyInfo(true));
                    stream.getSession().goAway();
                }
                else
                {
                    latch.countDown();
                }
                return null;
            }
        };
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                session.syn(new SynInfo(true), null);
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        session.syn(new SynInfo(true), null);

        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testDataNotProcessedAfterGoAway() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            private AtomicInteger syns = new AtomicInteger();

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                int synCount = syns.incrementAndGet();
                if (synCount == 1)
                {
                    return null;
                }
                else
                {
                    stream.getSession().goAway();
                    closeLatch.countDown();
                    return new StreamFrameListener.Adapter()
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
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
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
        Stream stream1 = session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                reply1Latch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        Assert.assertTrue(reply1Latch.await(5, TimeUnit.SECONDS));

        // Second stream is closed in the middle
        Stream stream2 = session.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        // There is a race between the data we want to send, and the client
        // closing the connection because the server closed it after the
        // go_away, so we guard with a try/catch to have the test pass cleanly
        try
        {
            stream2.data(new StringDataInfo("foo", true));
            Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
        }
        catch (SPDYException x)
        {
            Assert.assertThat(x.getCause(), CoreMatchers.instanceOf(ClosedChannelException.class));
        }

        // Be sure the last good stream is the first
        Assert.assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
        GoAwayInfo goAway = goAwayRef.get();
        Assert.assertNotNull(goAway);
        Assert.assertEquals(stream1.getId(), goAway.getLastStreamId());
    }
}
