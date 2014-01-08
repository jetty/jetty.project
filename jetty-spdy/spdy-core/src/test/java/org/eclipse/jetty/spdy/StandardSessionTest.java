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

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StandardSessionTest
{
    private static final Logger LOG = Log.getLogger(StandardSessionTest.class);
    private static final short VERSION = SPDY.V2;

    @Mock
    private Controller controller;

    @Mock
    private EndPoint endPoint;

    private ExecutorService threadPool;
    private StandardSession session;
    private Scheduler scheduler;
    private Fields headers;
    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final Generator generator = new Generator(bufferPool, new StandardCompressionFactory.StandardCompressor());

    @Before
    public void setUp() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
        session = new StandardSession(VERSION, bufferPool, scheduler, controller, endPoint, null, 1, null,
                generator, new FlowControlStrategy.None());
        when(endPoint.getIdleTimeout()).thenReturn(30000L);
        headers = new Fields();
    }

    @After
    public void after() throws Exception
    {
        scheduler.stop();
        threadPool.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private void setControllerWriteExpectation(final boolean fail)
    {
        doAnswer(new Answer()
        {
            public Object answer(InvocationOnMock invocation)
            {
                Object[] args = invocation.getArguments();
                Callback callback = (Callback)args[0];
                if (fail)
                    callback.failed(new ClosedChannelException());
                else
                    callback.succeeded();
                return null;
            }
        }).when(controller).write(any(Callback.class), any(ByteBuffer.class));
    }

    @Test
    public void testStreamIsRemovedFromSessionWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        assertThat("stream is not reset", stream.isReset(), is(false));
        session.rst(new RstInfo(stream.getId(), StreamStatus.STREAM_ALREADY_CLOSED));
        assertThatStreamIsNotInSession(stream);
        assertThatStreamIsReset(stream);
    }

    @Test
    public void testStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        stream.updateCloseState(true, true);
        session.onControlFrame(new SynReplyFrame(VERSION, SynInfo.FLAG_CLOSE, stream.getId(), null));
        assertThatStreamIsClosed(stream);
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsRemovedWhenHeadersWithCloseFlagAreSent() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        stream.updateCloseState(true, false);
        stream.headers(new HeadersInfo(headers, true));
        assertThatStreamIsClosed(stream);
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsUnidirectional() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        assertThat("stream is not unidirectional", stream.isUnidirectional(), not(true));
        Stream pushStream = createPushStream(stream);
        assertThat("pushStream is unidirectional", pushStream.isUnidirectional(), is(true));
    }

    @Test
    public void testPushStreamCreation() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        Stream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThat("Push stream must be associated to the first stream created", pushStream.getAssociatedStream().getId(), is(stream.getId()));
        assertThat("streamIds need to be monotonic", pushStream.getId(), greaterThan(stream.getId()));
    }

    @Test
    public void testPushStreamIsNotClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        Stream pushStream = createPushStream(stream);
        assertThatStreamIsNotHalfClosed(stream);
        assertThatStreamIsNotClosed(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsNotClosed(pushStream);

        stream.updateCloseState(true, true);
        assertThatStreamIsHalfClosed(stream);
        assertThatStreamIsNotClosed(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsNotClosed(pushStream);

        session.onControlFrame(new SynReplyFrame(VERSION, SynInfo.FLAG_CLOSE, stream.getId(), null));
        assertThatStreamIsClosed(stream);
        assertThatPushStreamIsNotClosed(pushStream);
    }

    @Test
    public void testCreatePushStreamOnClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        stream.updateCloseState(true, true);
        assertThatStreamIsHalfClosed(stream);
        stream.updateCloseState(true, false);
        assertThatStreamIsClosed(stream);
        createPushStreamAndMakeSureItFails(stream);
    }

    private void createPushStreamAndMakeSureItFails(IStream stream) throws InterruptedException
    {
        final CountDownLatch failedLatch = new CountDownLatch(1);
        PushInfo pushInfo = new PushInfo(5, TimeUnit.SECONDS, headers, false);
        stream.push(pushInfo, new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });
        assertThat("pushStream creation failed", failedLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testPushStreamIsAddedAndRemovedFromParentAndSessionWhenClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsInSession(pushStream);
        assertThatStreamIsAssociatedWithPushStream(stream, pushStream);
        session.data(pushStream, new StringDataInfo("close", true), 5, TimeUnit.SECONDS, new Callback.Adapter());
        assertThatPushStreamIsClosed(pushStream);
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream, pushStream);
    }

    @Test
    public void testPushStreamIsRemovedWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        IStream pushStream = (IStream)stream.push(new PushInfo(new Fields(), false));
        assertThatPushStreamIsInSession(pushStream);
        session.rst(new RstInfo(pushStream.getId(), StreamStatus.INVALID_STREAM));
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream, pushStream);
        assertThatStreamIsReset(pushStream);
    }

    @Test
    public void testPushStreamWithSynInfoClosedTrue() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        PushInfo pushInfo = new PushInfo(5, TimeUnit.SECONDS, headers, true);
        IStream pushStream = (IStream)stream.push(pushInfo);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream, pushStream);
        assertThatStreamIsNotInSession(pushStream);
    }

    @Test
    public void testPushStreamSendHeadersWithCloseFlagIsRemovedFromSessionAndDisassociateFromParent() throws InterruptedException, ExecutionException,
            TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = createStream();
        PushInfo pushInfo = new PushInfo(5, TimeUnit.SECONDS, headers, false);
        IStream pushStream = (IStream)stream.push(pushInfo);
        assertThatStreamIsAssociatedWithPushStream(stream, pushStream);
        assertThatPushStreamIsInSession(pushStream);
        pushStream.headers(new HeadersInfo(headers, true));
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream, pushStream);
    }

    @Test
    public void testCreatedAndClosedListenersAreCalledForNewStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(1);
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(createdListenerCalledLatch, closedListenerCalledLatch));
        IStream stream = createStream();
        session.onControlFrame(new SynReplyFrame(VERSION, (byte)0, stream.getId(), new Fields()));
        session.onDataFrame(new DataFrame(stream.getId(), SynInfo.FLAG_CLOSE, 128), ByteBuffer.allocate(128));
        stream.data(new StringDataInfo("close", true));
        assertThat("onStreamCreated listener has been called", createdListenerCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForResetStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null, closedListenerCalledLatch));
        IStream stream = createStream();
        session.rst(new RstInfo(stream.getId(), StreamStatus.CANCEL_STREAM));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testCreatedAndClosedListenersAreCalledForNewPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(2);
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(createdListenerCalledLatch, closedListenerCalledLatch));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.data(pushStream, new StringDataInfo("close", true), 5, TimeUnit.SECONDS, new Callback.Adapter());
        assertThat("onStreamCreated listener has been called twice. Once for the stream and once for the pushStream",
                createdListenerCalledLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForResetPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null, closedListenerCalledLatch));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.rst(new RstInfo(pushStream.getId(), StreamStatus.CANCEL_STREAM));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    private class TestStreamListener extends Session.StreamListener.Adapter
    {
        private CountDownLatch createdListenerCalledLatch;
        private CountDownLatch closedListenerCalledLatch;

        public TestStreamListener(CountDownLatch createdListenerCalledLatch, CountDownLatch closedListenerCalledLatch)
        {
            this.createdListenerCalledLatch = createdListenerCalledLatch;
            this.closedListenerCalledLatch = closedListenerCalledLatch;
        }

        @Override
        public void onStreamCreated(Stream stream)
        {
            if (createdListenerCalledLatch != null)
                createdListenerCalledLatch.countDown();
            super.onStreamCreated(stream);
        }

        @Override
        public void onStreamClosed(Stream stream)
        {
            if (closedListenerCalledLatch != null)
                closedListenerCalledLatch.countDown();
            super.onStreamClosed(stream);
        }
    }

    @Test
    @Ignore("In V3 we need to rst the stream if we receive data on a remotely half closed stream.")
    public void receiveDataOnRemotelyHalfClosedStreamResetsStreamInV3() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        IStream stream = (IStream)session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter());
        stream.updateCloseState(true, false);
        assertThat("stream is half closed from remote side", stream.isHalfClosed(), is(true));
        stream.process(new ByteBufferDataInfo(ByteBuffer.allocate(256), true));
    }

    @Test
    public void testReceiveDataOnRemotelyClosedStreamIsIgnored() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        final CountDownLatch onDataCalledLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        onDataCalledLatch.countDown();
                        super.onData(stream, dataInfo);
                    }
                });
        session.onControlFrame(new SynReplyFrame(VERSION, SynInfo.FLAG_CLOSE, stream.getId(), headers));
        session.onDataFrame(new DataFrame(stream.getId(), (byte)0, 0), ByteBuffer.allocate(128));
        assertThat("onData is never called", onDataCalledLatch.await(1, TimeUnit.SECONDS), not(true));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testControllerWriteFails() throws Exception
    {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicBoolean fail = new AtomicBoolean();
        Controller controller = new Controller()
        {
            @Override
            public void write(Callback callback, ByteBuffer... buffers)
            {
                writes.incrementAndGet();
                if (fail.get())
                    callback.failed(new ClosedChannelException());
                else
                    callback.succeeded();
            }

            @Override
            public void close(boolean onlyOutput)
            {

            }
        };
        ISession session = new StandardSession(VERSION, bufferPool, scheduler, controller, endPoint, null, 1, null, generator, null);
        IStream stream = new StandardStream(1, (byte)0, session, null, scheduler, null);
        stream.updateWindowSize(8192);

        // Send a reply to comply with the API usage
        stream.reply(new ReplyInfo(false), new Callback.Adapter());

        // Make the controller fail
        fail.set(true);
        final CountDownLatch failedCalledLatch = new CountDownLatch(1);
        Callback.Adapter callback = new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                failedCalledLatch.countDown();
            }
        };
        // Data frame should fail on controller.write()
        stream.data(new StringDataInfo(5, TimeUnit.SECONDS, "data", false), callback);

        Assert.assertEquals(2, writes.get());
        Assert.assertTrue(failedCalledLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testControlFramesAreStillSentForResetStreams() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectation(false);

        // This is necessary to keep the compression context of Headers valid
        IStream stream = createStream();
        session.rst(new RstInfo(stream.getId(), StreamStatus.INVALID_STREAM));
        stream.headers(new HeadersInfo(headers, true));

        verify(controller, times(3)).write(any(Callback.class), any(ByteBuffer.class));
    }

    @Test
    public void testMaxConcurrentStreams() throws InterruptedException
    {
        final CountDownLatch failedBecauseMaxConcurrentStreamsExceeded = new CountDownLatch(1);

        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.MAX_CONCURRENT_STREAMS, 0));
        SettingsFrame settingsFrame = new SettingsFrame(VERSION, (byte)0, settings);
        session.onControlFrame(settingsFrame);

        PushSynInfo pushSynInfo = new PushSynInfo(1, new PushInfo(new Fields(), false));
        session.syn(pushSynInfo, null, new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                failedBecauseMaxConcurrentStreamsExceeded.countDown();
            }
        });

        assertThat("Opening push stream failed because maxConcurrentStream is exceeded",
                failedBecauseMaxConcurrentStreamsExceeded.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testHeaderFramesAreSentInTheOrderTheyAreCreated() throws ExecutionException,
            TimeoutException, InterruptedException
    {
        testHeaderFramesAreSentInOrder((byte)0, (byte)0, (byte)0);
    }

    @Test
    public void testHeaderFramesAreSentInTheOrderTheyAreCreatedWithPrioritization() throws ExecutionException,
            TimeoutException, InterruptedException
    {
        testHeaderFramesAreSentInOrder((byte)0, (byte)1, (byte)2);
    }

    private void testHeaderFramesAreSentInOrder(final byte priority0, final byte priority1, final byte priority2) throws InterruptedException, ExecutionException
    {
        final StandardSession testLocalSession = new StandardSession(VERSION, bufferPool, scheduler,
                new ControllerMock(), endPoint, null, 1, null, generator, new FlowControlStrategy.None());
        HashSet<Future> tasks = new HashSet<>();

        int numberOfTasksToRun = 128;
        for (int i = 0; i < numberOfTasksToRun; i++)
        {
            tasks.add(threadPool.submit(new Runnable()
            {

                @Override
                public void run()
                {
                    synStream(priority0);
                    synStream(priority1);
                    synStream(priority2);
                }

                private void synStream(byte priority)
                {
                    SynInfo synInfo = new SynInfo(headers, false, priority);
                    testLocalSession.syn(synInfo, new StreamFrameListener.Adapter(), new FuturePromise<Stream>());
                }
            }));
        }

        for (Future task : tasks)
        {
            task.get();
        }

        threadPool.shutdown();
        threadPool.awaitTermination(60, TimeUnit.SECONDS);
    }

    private class ControllerMock implements Controller
    {
        long lastStreamId = 0;

        @Override
        public void write(Callback callback, ByteBuffer... buffers)
        {
            StandardSession.FrameBytes frameBytes = (StandardSession.FrameBytes)callback;

            int streamId = frameBytes.getStream().getId();
            LOG.debug("last: {}, current: {}", lastStreamId, streamId);
            if (lastStreamId < streamId)
                lastStreamId = streamId;
            else
                throw new IllegalStateException("Last streamId: " + lastStreamId + " is not smaller than current StreamId: " +
                        streamId);
            frameBytes.succeeded();
        }

        @Override
        public void close(boolean onlyOutput)
        {
        }
    }

    private IStream createStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(5, TimeUnit.SECONDS, headers, false, (byte)0);
        return (IStream)session.syn(synInfo, new StreamFrameListener.Adapter());
    }

    private IStream createPushStream(Stream stream) throws InterruptedException, ExecutionException, TimeoutException
    {
        PushInfo pushInfo = new PushInfo(5, TimeUnit.SECONDS, headers, false);
        return (IStream)stream.push(pushInfo);
    }

    private void assertThatStreamIsClosed(IStream stream)
    {
        assertThat("stream is closed", stream.isClosed(), is(true));
    }

    private void assertThatStreamIsReset(IStream stream)
    {
        assertThat("stream is reset", stream.isReset(), is(true));
    }

    private void assertThatStreamIsNotInSession(IStream stream)
    {
        assertThat("stream is not in session", session.getStreams().contains(stream), not(true));
    }

    private void assertThatStreamIsInSession(IStream stream)
    {
        assertThat("stream is in session", session.getStreams().contains(stream), is(true));
    }

    private void assertThatStreamIsNotClosed(IStream stream)
    {
        assertThat("stream is not closed", stream.isClosed(), not(true));
    }

    private void assertThatStreamIsNotHalfClosed(IStream stream)
    {
        assertThat("stream is not halfClosed", stream.isHalfClosed(), not(true));
    }

    private void assertThatPushStreamIsNotClosed(Stream pushStream)
    {
        assertThat("pushStream is not closed", pushStream.isClosed(), not(true));
    }

    private void assertThatStreamIsHalfClosed(IStream stream)
    {
        assertThat("stream is halfClosed", stream.isHalfClosed(), is(true));
    }

    private void assertThatStreamIsNotAssociatedWithPushStream(IStream stream, IStream pushStream)
    {
        assertThat("pushStream is removed from parent", stream.getPushedStreams().contains(pushStream), not(true));
    }

    private void assertThatPushStreamIsNotInSession(Stream pushStream)
    {
        assertThat("pushStream is not in session", session.getStreams().contains(pushStream), not(true));
    }

    private void assertThatPushStreamIsInSession(Stream pushStream)
    {
        assertThat("pushStream is in session", session.getStreams().contains(pushStream), is(true));
    }

    private void assertThatStreamIsAssociatedWithPushStream(IStream stream, Stream pushStream)
    {
        assertThat("stream is associated with pushStream", stream.getPushedStreams().contains(pushStream), is(true));
    }

    private void assertThatPushStreamIsClosed(Stream pushStream)
    {
        assertThat("pushStream is closed", pushStream.isClosed(), is(true));
    }

    private void assertThatPushStreamIsHalfClosed(Stream pushStream)
    {
        assertThat("pushStream is half closed ", pushStream.isHalfClosed(), is(true));
    }

    private void assertThatOnStreamClosedListenerHasBeenCalled(final CountDownLatch closedListenerCalledLatch) throws InterruptedException
    {
        assertThat("onStreamClosed listener has been called", closedListenerCalledLatch.await(5, TimeUnit.SECONDS), is(true));
    }
}
