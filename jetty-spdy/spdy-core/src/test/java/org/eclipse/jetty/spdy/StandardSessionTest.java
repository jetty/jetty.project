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

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StandardSessionTest
{
    @Mock
    private ISession sessionMock;
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
        session = new StandardSession(SPDY.V2,bufferPool,threadPool,scheduler,new TestController(),null,1,null,generator);
        headers = new Headers();
    }

    @Test
    public void testStreamGetsRemovedFromSessionWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        session.rst(new RstInfo(stream.getId(),StreamStatus.STREAM_ALREADY_CLOSED));
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
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
        IStream stream = createStream();
        assertThat("stream is added to session",session.getStreams().containsValue(stream),is(true));
        stream.updateCloseState(true,false);
        stream.headers(new HeadersInfo(headers,true));
        assertThatStreamIsClosed(stream);
        assertThat("stream is removed from session",session.getStreams().containsValue(stream),not(true));
    }

    @Test
    public void testStreamIsRemovedFromSessionWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        assertThatStreamIsInSession(stream);
        session.rst(new RstInfo(stream.getId(),StreamStatus.CANCEL_STREAM));
        assertThatStreamIsNotInSession(stream);
    }

    @Test
    public void testStreamIsUnidirectional() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        assertThat("stream is not unidirectional",stream.isUnidirectional(),not(true));
        Stream pushStream = createPushStream(stream);
        assertThat("pushStream is unidirectional",pushStream.isUnidirectional(),is(true));
    }

    @Test
    public void testPushStreamCreation() throws InterruptedException, ExecutionException, TimeoutException
    {
        Stream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThat("Push stream must be associated to the first stream created",pushStream.getAssociatedStream().getId(),is(stream.getId()));
        assertThat("streamIds need to be monotonic",pushStream.getId(),greaterThan(stream.getId()));
    }

    @Test
    public void testPushStreamIsNotClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
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

        stream.updateCloseState(true,false);
        assertThatStreamIsClosed(stream);
        assertThatPushStreamIsNotClosed(pushStream);
    }

    @Test
    public void testCreatePushStreamOnClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
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
        stream.syn(synInfo,5,TimeUnit.SECONDS,new Handler<Stream>()
        {
            @Override
            public void completed(Stream context)
            {
            }

            @Override
            public void failed(Throwable x)
            {
                failedLatch.countDown();
            }
        });
        assertThat("pushStream creation failed",failedLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testPushStreamIsAddedToParentAndSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        Stream pushStream = createPushStream(stream);
        assertThatStreamIsAssociatedWithPushStream(stream,pushStream);
        assertThatPushStreamIsInSession(pushStream);
    }

    @Test
    public void testPushStreamIsRemovedFromParentAndSessionWhenClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatStreamIsAssociatedWithPushStream(stream,pushStream);
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.CANCEL_STREAM));
        pushStream.updateCloseState(true,true);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
        assertThatPushStreamIsNotInSession(pushStream);
    }

    @Test
    public void testPushStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        assertThatPushStreamIsInSession(pushStream);
        session.data(pushStream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThatPushStreamIsNotInSession(pushStream);
    }

    @Test
    public void testPushStreamIsRemovedWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        IStream pushStream = (IStream)stream.syn(new SynInfo(false)).get();
        assertThatPushStreamIsInSession(pushStream);
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.INVALID_STREAM));
        assertThatPushStreamIsNotInSession(pushStream);
    }

    @Test
    public void testPushStreamWithSynInfoClosedTrue() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = createStream();
        SynInfo synInfo = new SynInfo(headers,true,stream.getPriority());
        IStream pushStream = (IStream)stream.syn(synInfo).get(5,TimeUnit.SECONDS);
        assertThatPushStreamIsHalfClosed(pushStream);
        assertThatPushStreamIsClosed(pushStream);
        assertThatStreamIsNotAssociatedWithPushStream(stream,pushStream);
    }

    @Test
    public void testPushStreamSendHeadersWithCloseFlagIsRemovedFromSessionAndDisassociateFromParent() throws InterruptedException, ExecutionException,
            TimeoutException
    {
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
    public void testListenerIsCalledForNewStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(createdListenerCalledLatch,null));
        IStream stream = createStream();
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,128),ByteBuffer.allocate(128));
        session.data(stream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThat("onStreamCreated listener has been called",createdListenerCalledLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForResetStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null,closedListenerCalledLatch));
        IStream stream = createStream();
        session.rst(new RstInfo(stream.getId(),StreamStatus.CANCEL_STREAM));
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null,closedListenerCalledLatch));
        IStream stream = createStream();
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,128),ByteBuffer.allocate(128));
        session.data(stream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForNewPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(2);
        session.addListener(new TestStreamListener(createdListenerCalledLatch,null));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.data(pushStream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThat("onStreamCreated listener has been called twice. Once for the stream and once for the pushStream",
                createdListenerCalledLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForClosedPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new TestStreamListener(null,closedListenerCalledLatch));
        IStream stream = createStream();
        IStream pushStream = createPushStream(stream);
        session.data(pushStream,new StringDataInfo("close",true),5,TimeUnit.SECONDS,null,null);
        assertThatOnStreamClosedListenerHasBeenCalled(closedListenerCalledLatch);
    }

    @Test
    public void testListenerIsCalledForResetPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
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

    @SuppressWarnings("unchecked")
    @Test(expected = IllegalStateException.class)
    public void testSendDataOnHalfClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynStreamFrame synStreamFrame = new SynStreamFrame(SPDY.V2,SynInfo.FLAG_CLOSE,1,0,(byte)0,null);
        IStream stream = new StandardStream(synStreamFrame,sessionMock,8184,null);
        stream.updateCloseState(synStreamFrame.isClose(),true);
        assertThat("stream is half closed",stream.isHalfClosed(),is(true));
        stream.data(new StringDataInfo("data on half closed stream",true));
        verify(sessionMock,never()).data(any(IStream.class),any(DataInfo.class),anyInt(),any(TimeUnit.class),any(Handler.class),any(void.class));
    }

    @Test
    @Ignore("In V3 we need to rst the stream if we receive data on a remotely half closed stream.")
    public void receiveDataOnRemotelyHalfClosedStreamResetsStreamInV3() throws InterruptedException, ExecutionException
    {
        IStream stream = (IStream)session.syn(new SynInfo(false),new StreamFrameListener.Adapter()).get();
        stream.updateCloseState(true,false);
        assertThat("stream is half closed from remote side",stream.isHalfClosed(),is(true));
        stream.process(new DataFrame(stream.getId(),(byte)0,256),ByteBuffer.allocate(256));
    }

    @Test
    public void testReceiveDataOnRemotelyClosedStreamIsIgnored() throws InterruptedException, ExecutionException, TimeoutException
    {
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

    private IStream createStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(headers,false,(byte)0);
        return (IStream)session.syn(synInfo,new StreamFrameListener.Adapter()).get(5,TimeUnit.SECONDS);
    }

    private IStream createPushStream(Stream stream) throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        return (IStream)stream.syn(synInfo).get(5,TimeUnit.SECONDS);
    }

    private static class TestController implements Controller<StandardSession.FrameBytes>
    {
        @Override
        public int write(ByteBuffer buffer, Handler<StandardSession.FrameBytes> handler, StandardSession.FrameBytes context)
        {
            handler.completed(context);
            return buffer.remaining();
        }

        @Override
        public void close(boolean onlyOutput)
        {
        }
    }

    private void assertThatStreamIsClosed(IStream stream)
    {
        assertThat("stream is closed",stream.isClosed(),is(true));
    }

    private void assertThatStreamIsNotInSession(IStream stream)
    {
        assertThat("stream is not in session",session.getStreams().containsValue(stream),not(true));
    }

    private void assertThatStreamIsInSession(IStream stream)
    {
        assertThat("stream is in session",session.getStreams().containsValue(stream),is(true));
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
        assertThat("pushStream is in session",session.getStreams().containsKey(pushStream.getId()),not(true));
    }

    private void assertThatPushStreamIsInSession(Stream pushStream)
    {
        assertThat("pushStream is in session",session.getStreams().containsKey(pushStream.getId()),is(true));
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
