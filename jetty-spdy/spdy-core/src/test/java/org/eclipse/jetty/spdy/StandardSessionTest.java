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

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.StandardSession.FrameBytes;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StandardSessionTest
{
    @Mock
    private Controller<FrameBytes> controller;

    private ByteBufferPool bufferPool;
    private Executor threadPool;
    private StandardSession session;
    private Generator generator;
    private ScheduledExecutorService scheduler;
    private Headers headers;

    @Before
    public void setUp() throws Exception
    {
        bufferPool = new StandardByteBufferPool();
        threadPool = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        generator = new Generator(new StandardByteBufferPool(),new StandardCompressionFactory.StandardCompressor());
        session = new StandardSession(SPDY.V2,bufferPool,threadPool,scheduler,controller,null,1,null,generator,new FlowControlStrategy.None());
        headers = new Headers();
    }

    @SuppressWarnings("unchecked")
    private void setControllerWriteExpectationToFail(final boolean fail)
    {
        when(controller.write(any(ByteBuffer.class),any(Handler.class),any(StandardSession.FrameBytes.class))).thenAnswer(new Answer<Integer>()
        {
            public Integer answer(InvocationOnMock invocation)
            {
                Object[] args = invocation.getArguments();

                Handler<StandardSession.FrameBytes> handler = (Handler<FrameBytes>)args[1];
                FrameBytes context = (FrameBytes)args[2];

                if (fail)
                    handler.failed(context,new ClosedChannelException());
                else
                    handler.completed(context);
                return 0;
            }
        });
    }

    @Test
    public void testStreamIsRemovedFromSessionWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        assertThat("stream is not reset",stream.isReset(),is(false));
        session.rst(new RstInfo(stream.getId(),StreamStatus.STREAM_ALREADY_CLOSED));
        assertThatStreamIsNotInSession(stream);
        assertThatStreamIsReset(stream);
    }

    @Test
    public void testStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        stream.updateCloseState(true,true);
        session.onControlFrame(new SynReplyFrame(SPDY.V2,SynInfo.FLAG_CLOSE,stream.getId(),null));
        assertThatStreamIsClosed(stream);
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsRemovedWhenHeadersWithCloseFlagAreSent() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        stream.updateCloseState(true,false);
        stream.headers(new HeadersInfo(headers,true));
        assertThatStreamIsClosed(stream);
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsUnidirectional() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        assertThat("stream is not unidirectional",stream.isUnidirectional(),not(true));
        Stream pushStream = createPushStream(stream);
        assertThat("pushStream is unidirectional",pushStream.isUnidirectional(),is(true));
    }

    @Test
    public void testPushStreamCreation() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        Stream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThat("Push stream must be associated to the first stream created",pushStream.getAssociatedStream().getId(),is(stream.getId()));
        assertThat("streamIds need to be monotonic",pushStream.getId(),greaterThan(stream.getId()));
    }

    @Test
    public void testPushStreamIsNotClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        Stream pushStream = createPushStream(stream);
        assertThatStreamIsNotHalfClosed(stream);
        assertThatStreamIsNotClosed(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsNotClosed(pushStream);

        stream.updateCloseState(true,true);
        assertThatStreamIsHalfClosed(stream);
        assertThatStreamIsNotClosed(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsNotClosed(pushStream);

        session.onControlFrame(new SynReplyFrame(SPDY.V2,SynInfo.FLAG_CLOSE,stream.getId(),null));
        assertThatStreamIsClosed(stream);
        assertThatPushStreamIsNotClosed(pushStream);
    }

    @Test
    public void testCreatePushStreamOnClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        stream.updateCloseState(true,true);
        assertThatStreamIsHalfClosed(stream);
        stream.updateCloseState(true,false);
        assertThatStreamIsClosed(stream);
        createPushStreamAndMakeSureItFails(stream);
    }

    private void createPushStreamAndMakeSureItFails(IStream stream) throws InterruptedException
    {
        final CountDownLatch failedLatch = new CountDownLatch(1);
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        stream.syn(synInfo,5,TimeUnit.SECONDS,new Handler.Adapter<Stream>()
        {
            @Override
            public void failed(Stream stream, Throwable x)
            {
                failedLatch.countDown();
            }
        });
        assertThat("pushStream creation failed",failedLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testPushStreamIsAddedAndRemovedFromParentAndSessionWhenClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsInSession(pushStream);
        assertThatStreamIsAssociatedWithPushStream(stream,pushStream);
        session.data(pushStream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThatPushStreamIsClosed(pushStream);
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
    }

    @Test
    public void testPushStreamIsRemovedWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        IStream pushStream = (IStream)stream.syn(new SynInfo(false)).get();
        assertThatPushStreamIsInSession(pushStream);
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.INVALID_STREAM));
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
        assertThatStreamIsReset(pushStream);
    }

    @Test
    public void testPushStreamWithSynInfoClosedTrue() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        SynInfo synInfo = new SynInfo(headers,true,stream.getPriority());
        IStream pushStream = (IStream)stream.syn(synInfo).get(5,TimeUnit.SECONDS);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
        assertThatStreamIsNotInSession(pushStream);
    }

    @Test
    public void testPushStreamSendHeadersWithCloseFlagIsRemovedFromSessionAndDisassociateFromParent() throws InterruptedException, ExecutionException,
            TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = createStream();
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        IStream pushStream = (IStream)stream.syn(synInfo).get(5,TimeUnit.SECONDS);
        assertThatStreamIsAssociatedWithPushStream(stream,pushStream);
        assertThatPushStreamIsInSession(pushStream);
        pushStream.headers(new HeadersInfo(headers,true));
        assertThatPushStreamIsNotInSession(pushStream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
    }

    @Test
    public void testCreatedAndClosedListenersAreCalledForNewStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(1);
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(createdListenerCalledLatch,closedListenerCalledLatch));
        IStream stream = createStream();
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,128),ByteBuffer.allocate(128));
        stream.data(new StringDataInfo("close",true));
        assertThat("onStreamCreated listener has been called",createdListenerCalledLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForResetStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null,closedListenerCalledLatch));
        IStream stream = createStream();
        session.rst(new RstInfo(stream.getId(),StreamStatus.CANCEL_STREAM));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testCreatedAndClosedListenersAreCalledForNewPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(2);
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(createdListenerCalledLatch,closedListenerCalledLatch));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.data(pushStream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThat("onStreamCreated listener has been called twice. Once for the stream and once for the pushStream",
                createdListenerCalledLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForResetPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null,closedListenerCalledLatch));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.CANCEL_STREAM));
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
    public void receiveDataOnRemotelyHalfClosedStreamResetsStreamInV3() throws InterruptedException, ExecutionException
    {
        setControllerWriteExpectationToFail(false);

        IStream stream = (IStream)session.syn(new SynInfo(false),new StreamFrameListener.Adapter()).get();
        stream.updateCloseState(true,false);
        assertThat("stream is half closed from remote side",stream.isHalfClosed(),is(true));
        stream.process(new ByteBufferDataInfo(ByteBuffer.allocate(256), true));
    }

    @Test
    public void testReceiveDataOnRemotelyClosedStreamIsIgnored() throws InterruptedException, ExecutionException, TimeoutException
    {
        setControllerWriteExpectationToFail(false);

        final CountDownLatch onDataCalledLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(false),new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                onDataCalledLatch.countDown();
                super.onData(stream,dataInfo);
            }
        }).get(5,TimeUnit.SECONDS);
        session.onControlFrame(new SynReplyFrame(SPDY.V2,SynInfo.FLAG_CLOSE,stream.getId(),headers));
        session.onDataFrame(new DataFrame(stream.getId(),(byte)0,0),ByteBuffer.allocate(128));
        assertThat("onData is never called",onDataCalledLatch.await(1,TimeUnit.SECONDS),not(true));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testControllerWriteFailsInEndPointFlush() throws InterruptedException
    {
        setControllerWriteExpectationToFail(true);

        final CountDownLatch failedCalledLatch = new CountDownLatch(2);
        SynStreamFrame synStreamFrame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, null);
        IStream stream = new StandardStream(synStreamFrame.getStreamId(), synStreamFrame.getPriority(), session, null);
        stream.updateWindowSize(8192);
        Handler.Adapter<Void> handler = new Handler.Adapter<Void>()
        {
            @Override
            public void failed(Void context, Throwable x)
            {
                failedCalledLatch.countDown();
            }
        };

        // first data frame should fail on controller.write()
        stream.data(new StringDataInfo("data", false), 5, TimeUnit.SECONDS, handler);
        // second data frame should fail without controller.writer() as the connection is expected to be broken after first controller.write() call failed.
        stream.data(new StringDataInfo("data", false), 5, TimeUnit.SECONDS, handler);

        verify(controller, times(1)).write(any(ByteBuffer.class), any(Handler.class), any(FrameBytes.class));
        assertThat("Handler.failed has been called twice", failedCalledLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private IStream createStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(headers,false,(byte)0);
        return (IStream)session.syn(synInfo,new StreamFrameListener.Adapter()).get(50,TimeUnit.SECONDS);
    }

    private IStream createPushStream(Stream stream) throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        return (IStream)stream.syn(synInfo).get(5,TimeUnit.SECONDS);
    }

    private void assertThatStreamIsClosed(IStream stream)
    {
        assertThat("stream is closed",stream.isClosed(),is(true));
    }

    private void assertThatStreamIsReset(IStream stream)
    {
        assertThat("stream is reset",stream.isReset(),is(true));
    }

    private void assertThatStreamIsNotInSession(IStream stream)
    {
        assertThat("stream is not in session",session.getStreams().contains(stream),not(true));
    }

    private void assertThatStreamIsInSession(IStream stream)
    {
        assertThat("stream is in session",session.getStreams().contains(stream),is(true));
    }

    private void assertThatStreamIsNotClosed(IStream stream)
    {
        assertThat("stream is not closed",stream.isClosed(),not(true));
    }

    private void assertThatStreamIsNotHalfClosed(IStream stream)
    {
        assertThat("stream is not halfClosed",stream.isHalfClosed(),not(true));
    }

    private void assertThatPushStreamIsNotClosed(Stream pushStream)
    {
        assertThat("pushStream is not closed",pushStream.isClosed(),not(true));
    }

    private void assertThatStreamIsHalfClosed(IStream stream)
    {
        assertThat("stream is halfClosed",stream.isHalfClosed(),is(true));
    }

    private void assertThatStreamIsNotAssociatedWithPushStream(IStream stream, IStream pushStream)
    {
        assertThat("pushStream is removed from parent",stream.getPushedStreams().contains(pushStream),not(true));
    }

    private void assertThatPushStreamIsNotInSession(Stream pushStream)
    {
        assertThat("pushStream is not in session",session.getStreams().contains(pushStream.getId()),not(true));
    }

    private void assertThatPushStreamIsInSession(Stream pushStream)
    {
        assertThat("pushStream is in session",session.getStreams().contains(pushStream),is(true));
    }

    private void assertThatStreamIsAssociatedWithPushStream(IStream stream, Stream pushStream)
    {
        assertThat("stream is associated with pushStream",stream.getPushedStreams().contains(pushStream),is(true));
    }

    private void assertThatPushStreamIsClosed(Stream pushStream)
    {
        assertThat("pushStream is closed",pushStream.isClosed(),is(true));
    }

    private void assertThatPushStreamIsHalfClosed(Stream pushStream)
    {
        assertThat("pushStream is half closed ",pushStream.isHalfClosed(),is(true));
    }

    private void assertThatOnStreamClosedListenerHasBeenCalled(final CountDownLatch closedListenerCalledLatch) throws InterruptedException
    {
        assertThat("onStreamClosed listener has been called",closedListenerCalledLatch.await(5,TimeUnit.SECONDS),is(true));
    }
}
