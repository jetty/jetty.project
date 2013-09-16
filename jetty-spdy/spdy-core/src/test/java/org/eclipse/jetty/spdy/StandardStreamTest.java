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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StandardStreamTest
{
    @Mock
    private ISession session;
    @Mock
    private SynStreamFrame synStreamFrame;

    /**
     * Test method for {@link org.eclipse.jetty.spdy.StandardStream#syn(org.eclipse.jetty.spdy.api.SynInfo)}.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testSyn()
    {
        Stream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session, null);
        Set<Stream> streams = new HashSet<>();
        streams.add(stream);
        when(synStreamFrame.isClose()).thenReturn(false);
        SynInfo synInfo = new SynInfo(false);
        when(session.getStreams()).thenReturn(streams);
        stream.syn(synInfo);
        verify(session).syn(argThat(new PushSynInfoMatcher(stream.getId(), synInfo)), any(StreamFrameListener.class), anyLong(), any(TimeUnit.class), any(Handler.class));
    }

    private class PushSynInfoMatcher extends ArgumentMatcher<PushSynInfo>
    {
        int associatedStreamId;
        SynInfo synInfo;

        public PushSynInfoMatcher(int associatedStreamId, SynInfo synInfo)
        {
            this.associatedStreamId = associatedStreamId;
            this.synInfo = synInfo;
        }

        @Override
        public boolean matches(Object argument)
        {
            PushSynInfo pushSynInfo = (PushSynInfo)argument;
            if (pushSynInfo.getAssociatedStreamId() != associatedStreamId)
            {
                System.out.println("streamIds do not match!");
                return false;
            }
            if (pushSynInfo.isClose() != synInfo.isClose())
            {
                System.out.println("isClose doesn't match");
                return false;
            }
            return true;
        }
    }

    @Test
    public void testSynOnClosedStream()
    {
        IStream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session, null);
        stream.updateCloseState(true, true);
        stream.updateCloseState(true, false);
        assertThat("stream expected to be closed", stream.isClosed(), is(true));
        final CountDownLatch failedLatch = new CountDownLatch(1);
        stream.syn(new SynInfo(false), 1, TimeUnit.SECONDS, new Handler.Adapter<Stream>()
        {
            @Override
            public void failed(Stream stream, Throwable x)
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
        IStream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session, null);
        stream.updateWindowSize(8192);
        stream.updateCloseState(synStreamFrame.isClose(), true);
        assertThat("stream is half closed", stream.isHalfClosed(), is(true));
        stream.data(new StringDataInfo("data on half closed stream", true));
        verify(session, never()).data(any(IStream.class), any(DataInfo.class), anyInt(), any(TimeUnit.class), any(Handler.class), any(void.class));
    }
}
