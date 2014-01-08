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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.Test;

public class ResetStreamTest extends AbstractTest
{
    @Test
    public void testResetStreamIsRemoved() throws Exception
    {
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()/*TODO, true*/), null);

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        session.rst(new RstInfo(5, TimeUnit.SECONDS, stream.getId(), StreamStatus.CANCEL_STREAM));

        assertEquals("session expected to contain 0 streams", 0, session.getStreams().size());
    }

    @Test
    public void testRefusedStreamIsRemoved() throws Exception
    {
        final AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch rstLatch = new CountDownLatch(1);
        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Session serverSession = stream.getSession();
                serverSessionRef.set(serverSession);
                serverSession.rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new FutureCallback());
                synLatch.countDown();
                return null;
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                rstLatch.countDown();
            }
        });

        Stream stream = clientSession.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);

        assertTrue("syncLatch didn't count down", synLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = serverSessionRef.get();
        assertEquals("serverSession expected to contain 0 streams", 0, serverSession.getStreams().size());

        assertTrue("rstLatch didn't count down", rstLatch.await(5, TimeUnit.SECONDS));
        // Need to sleep a while to give the chance to the implementation to remove the stream
        TimeUnit.SECONDS.sleep(1);
        assertTrue("stream is expected to be reset", stream.isReset());
        assertEquals("clientSession expected to contain 0 streams", 0, clientSession.getStreams().size());
    }

    @Test
    public void testRefusedStreamIgnoresData() throws Exception
    {
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch rstLatch = new CountDownLatch(1);
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    // Refuse the stream, we must ignore data frames
                    assertTrue(synLatch.await(5, TimeUnit.SECONDS));
                    stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new FutureCallback());
                    return new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            dataLatch.countDown();
                        }
                    };
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                    return null;
                }
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                rstLatch.countDown();
            }
        });

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        stream.data(new StringDataInfo(5, TimeUnit.SECONDS, "data", true), new Callback.Adapter()
        {
            @Override
            public void succeeded()
            {
                synLatch.countDown();
            }
        });

        assertTrue("rstLatch didn't count down", rstLatch.await(5, TimeUnit.SECONDS));
        assertTrue("stream is expected to be reset", stream.isReset());
        assertFalse("dataLatch shouldn't be count down", dataLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testResetAfterServerReceivedFirstDataFrameAndSecondDataFrameFails() throws Exception
    {
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch rstLatch = new CountDownLatch(1);
        final CountDownLatch failLatch = new CountDownLatch(1);
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                synLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataLatch.countDown();
                        stream.getSession().rst(new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM), new FutureCallback());
                    }
                };
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                rstLatch.countDown();
            }
        });

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        assertThat("push is received by server", synLatch.await(5, TimeUnit.SECONDS), is(true));
        stream.data(new StringDataInfo(5, TimeUnit.SECONDS, "data", false), new Callback.Adapter());
        assertThat("stream is reset", rstLatch.await(5, TimeUnit.SECONDS), is(true));
        stream.data(new StringDataInfo(5, TimeUnit.SECONDS, "2nd dataframe", false), new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                failLatch.countDown();
            }
        });

        assertThat("2nd data call failed", failLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("stream is reset", stream.isReset(), is(true));
    }

    // TODO: If server already received 2nd dataframe after it rst, it should ignore it. Not easy to do.

}
