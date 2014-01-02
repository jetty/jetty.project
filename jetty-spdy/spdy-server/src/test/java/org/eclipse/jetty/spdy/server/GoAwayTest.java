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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
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
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                return null;
            }

            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                Assert.assertEquals(0, goAwayInfo.getLastStreamId());
                Assert.assertSame(SessionStatus.OK, goAwayInfo.getSessionStatus());
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), null);

        session.syn(new SynInfo(new Fields(), true), null);

        session.goAway(new GoAwayInfo());

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
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                stream.getSession().goAway(new GoAwayInfo(), new FutureCallback());
                return null;
            }
        };
        final AtomicReference<GoAwayResultInfo> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                ref.set(goAwayInfo);
                latch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Stream stream1 = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), null);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        GoAwayResultInfo goAwayResultInfo = ref.get();
        Assert.assertNotNull(goAwayResultInfo);
        Assert.assertEquals(stream1.getId(), goAwayResultInfo.getLastStreamId());
        Assert.assertSame(SessionStatus.OK, goAwayResultInfo.getSessionStatus());
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
                    stream.reply(new ReplyInfo(true), new Callback.Adapter());
                    stream.getSession().goAway(new GoAwayInfo(), new FutureCallback());
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
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                session.syn(new SynInfo(new Fields(), true), null, new FuturePromise<Stream>());
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        session.syn(new SynInfo(new Fields(), true), null);

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
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                int synCount = syns.incrementAndGet();
                if (synCount == 1)
                {
                    return null;
                }
                else
                {
                    stream.getSession().goAway(new GoAwayInfo(), new FutureCallback());
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
        final AtomicReference<GoAwayResultInfo> goAwayRef = new AtomicReference<>();
        final CountDownLatch goAwayLatch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                goAwayRef.set(goAwayInfo);
                goAwayLatch.countDown();
            }
        };
        Session session = startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        // First stream is processed ok
        final CountDownLatch reply1Latch = new CountDownLatch(1);
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                reply1Latch.countDown();
            }
        });
        Assert.assertTrue(reply1Latch.await(5, TimeUnit.SECONDS));

        // Second stream is closed in the middle
        Stream stream2 = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        // There is a race between the data we want to send, and the client
        // closing the connection because the server closed it after the
        // go_away, so we guard with a try/catch to have the test pass cleanly
        try
        {
            stream2.data(new StringDataInfo("foo", true));
            Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
        }
        catch (ExecutionException x)
        {
            // doesn't matter which exception we get, it's important that the data is not been written and the
            // previous assertion is true
        }

        // The last good stream is the second, because it was received by the server
        Assert.assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
        GoAwayResultInfo goAway = goAwayRef.get();
        Assert.assertNotNull(goAway);
        Assert.assertEquals(stream2.getId(), goAway.getLastStreamId());
    }
}
