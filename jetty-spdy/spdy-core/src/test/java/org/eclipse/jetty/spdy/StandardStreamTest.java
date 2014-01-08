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

package org.eclipse.jetty.spdy;

import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StandardStreamTest
{
    private final ScheduledExecutorScheduler scheduler = new ScheduledExecutorScheduler();
    @Mock
    private ISession session;
    @Mock
    private SynStreamFrame synStreamFrame;

    @Before
    public void setUp() throws Exception
    {
        scheduler.start();
    }

    @After
    public void tearDown() throws Exception
    {
        scheduler.stop();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSyn()
    {
        Stream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session, null, null, null);
        Set<Stream> streams = new HashSet<>();
        streams.add(stream);
        when(synStreamFrame.isClose()).thenReturn(false);
        PushInfo pushInfo = new PushInfo(new Fields(), false);
        when(session.getStreams()).thenReturn(streams);
        stream.push(pushInfo, new Promise.Adapter<Stream>());
        verify(session).syn(argThat(new PushSynInfoMatcher(stream.getId(), pushInfo)),
                any(StreamFrameListener.class), any(Promise.class));
    }

    private class PushSynInfoMatcher extends ArgumentMatcher<PushSynInfo>
    {
        private int associatedStreamId;
        private PushInfo pushInfo;

        public PushSynInfoMatcher(int associatedStreamId, PushInfo pushInfo)
        {
            this.associatedStreamId = associatedStreamId;
            this.pushInfo = pushInfo;
        }

        @Override
        public boolean matches(Object argument)
        {
            PushSynInfo pushSynInfo = (PushSynInfo)argument;
            return pushSynInfo.getAssociatedStreamId() == associatedStreamId && pushSynInfo.isClose() == pushInfo.isClose();
        }
    }

    @Test
    public void testSynOnClosedStream()
    {
        IStream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session,
                null, null , null);
        stream.updateCloseState(true, true);
        stream.updateCloseState(true, false);
        assertThat("stream expected to be closed", stream.isClosed(), is(true));
        final CountDownLatch failedLatch = new CountDownLatch(1);
        stream.push(new PushInfo(1, TimeUnit.SECONDS, new Fields(), false), new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });
        assertThat("PushStream creation failed", failedLatch.getCount(), equalTo(0L));
    }

    @SuppressWarnings("unchecked")
    @Test(expected = IllegalStateException.class)
    public void testSendDataOnHalfClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynStreamFrame synStreamFrame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, null);
        IStream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session,
                null, scheduler, null);
        stream.updateWindowSize(8192);
        stream.updateCloseState(synStreamFrame.isClose(), true);
        assertThat("stream is half closed", stream.isHalfClosed(), is(true));
        stream.data(new StringDataInfo("data on half closed stream", true));
        verify(session, never()).data(any(IStream.class), any(DataInfo.class), anyInt(), any(TimeUnit.class), any(Callback.class));
    }

    @Test
    @Slow
    public void testIdleTimeout() throws Exception
    {
        IStream stream = new StandardStream(1, (byte)0, session, null, scheduler, null);
        long idleTimeout = 500;
        stream.setIdleTimeout(idleTimeout);

        final AtomicInteger failureCount = new AtomicInteger();
        final CountDownLatch failureLatch = new CountDownLatch(1);
        stream.setStreamFrameListener(new StreamFrameListener.Adapter()
        {
            @Override
            public void onFailure(Stream stream, Throwable x)
            {
                assertThat("exception is a TimeoutException", x, is(instanceOf(TimeoutException.class)));
                failureCount.incrementAndGet();
                failureLatch.countDown();
            }
        });
        stream.process(new StringDataInfo("string", false));

        // Wait more than (2 * idleTimeout) to be sure to trigger a failureCount > 1
        Thread.sleep(3 * idleTimeout);

        assertThat("onFailure has been called", failureLatch.await(5, TimeUnit.SECONDS), is(true));
        Assert.assertEquals(1, failureCount.get());
    }

    @Test
    @Slow
    public void testIdleTimeoutIsInterruptedWhenReceiving() throws Exception
    {
        final CountDownLatch failureLatch = new CountDownLatch(1);
        IStream stream = new StandardStream(1, (byte)0, session, null, scheduler, null);
        long idleTimeout = 1000;
        stream.setIdleTimeout(idleTimeout);
        stream.setStreamFrameListener(new StreamFrameListener.Adapter()
        {
            @Override
            public void onFailure(Stream stream, Throwable x)
            {
                assertThat("exception is a TimeoutException", x, is(instanceOf(TimeoutException.class)));
                failureLatch.countDown();
            }
        });
        stream.process(new SynStreamFrame(SPDY.V3, (byte)0, 1, 0, (byte)0, (short)0, null));
        stream.process(new StringDataInfo("string", false));
        Thread.sleep(idleTimeout / 2);
        stream.process(new StringDataInfo("string", false));
        Thread.sleep(idleTimeout / 2);
        stream.process(new StringDataInfo("string", false));
        Thread.sleep(idleTimeout / 2);
        stream.process(new StringDataInfo("string", true));
        stream.reply(new ReplyInfo(true), new Callback.Adapter());
        Thread.sleep(idleTimeout);
        assertThat("onFailure has not been called", failureLatch.await(idleTimeout, TimeUnit.MILLISECONDS), is(false));
    }

    @Test
    @Slow
    public void testReplyFailureClosesStream() throws Exception
    {
        ISession session = new StandardSession(SPDY.V3, null, null, null, null, null, 1, null, null, null)
        {
            @Override
            public void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Callback callback)
            {
                callback.failed(new ClosedChannelException());
            }
        };
        IStream stream = new StandardStream(1, (byte)0, session, null, scheduler, null);
        final AtomicInteger failureCount = new AtomicInteger();
        stream.setStreamFrameListener(new StreamFrameListener.Adapter()
        {
            @Override
            public void onFailure(Stream stream, Throwable x)
            {
                failureCount.incrementAndGet();
            }
        });
        long idleTimeout = 500;
        stream.setIdleTimeout(idleTimeout);

        stream.process(new SynStreamFrame(SPDY.V3, (byte)0, 1, 0, (byte)0, (short)0, null));

        final CountDownLatch failureLatch = new CountDownLatch(1);
        stream.reply(new ReplyInfo(false), new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                failureLatch.countDown();
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        // Make sure that the idle timeout never fires, since the failure above should have closed the stream
        Thread.sleep(3 * idleTimeout);

        Assert.assertEquals(0, failureCount.get());
        Assert.assertTrue(stream.isClosed());
    }
}
