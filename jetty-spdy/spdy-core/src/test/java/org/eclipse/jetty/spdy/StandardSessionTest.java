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

import static org.hamcrest.Matchers.equalTo;
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
import java.util.concurrent.Future;
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
    public void testResetStreamGetsRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        Stream stream = createStream();
        assertThat("1 stream in session",session.getStreams().size(),is(1));
        session.rst(new RstInfo(stream.getId(),StreamStatus.STREAM_ALREADY_CLOSED));
        assertThat("stream is removed",session.getStreams().size(),is(0));
    }

    @Test
    public void testServerPush() throws InterruptedException, ExecutionException, TimeoutException
    {
        Stream stream = createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("Push stream must be associated to the first stream created",pushStream.getParentStream().getId(),is(stream.getId()));
        assertThat("streamIds need to be monotonic",pushStream.getId(),greaterThan(stream.getId()));
    }

    @Test
    public void testPushStreamIsNotClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("stream is not halfClosed",stream.isHalfClosed(),is(false));
        assertThat("stream is not closed",stream.isClosed(),is(false));
        assertThat("pushStream is halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream is not closed",pushStream.isClosed(),is(false));

        stream.updateCloseState(true,true);
        assertThat("stream is halfClosed",stream.isHalfClosed(),is(true));
        assertThat("stream is not closed",stream.isClosed(),is(false));
        assertThat("pushStream is halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream is not closed",pushStream.isClosed(),is(false));

        stream.updateCloseState(true,false);
        assertThat("stream is closed",stream.isClosed(),is(true));
        assertThat("pushStream is not closed",pushStream.isClosed(),is(false));
    }

    @Test
    public void testCreatePushStreamOnClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        stream.updateCloseState(true,true);
        assertThat("stream is halfClosed",stream.isHalfClosed(),is(true));
        stream.updateCloseState(true,false);
        assertThat("stream is closed",stream.isClosed(),is(true));
        createPushStreamAndMakeSureItFails(stream);
    }

    private void createPushStreamAndMakeSureItFails(IStream stream)
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
        assertThat("pushStream creation failed",failedLatch.getCount(),equalTo(0L));
    }

    @Test
    public void testPushStreamIsAddedToParentAndSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("pushStream is added to parent",stream.getAssociatedStreams().contains(pushStream),is(true));
        assertThat("pushStream is added to session",session.getStreams().containsValue(pushStream),is(true));
    }

    @Test
    public void testStreamIsUnidirectional() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        assertThat("stream is not unidirectional",stream.isUnidirectional(),is(false));
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("pushStream is unidirectional",pushStream.isUnidirectional(),is(true));
    }

    @Test
    public void testPushStreamIsRemovedFromParentAndSessionWhenClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("pushStream is halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream is added to parent",stream.getAssociatedStreams().contains(pushStream),is(true));
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.CANCEL_STREAM));
        pushStream.updateCloseState(true,true);
        assertThat("pushStream is closed",pushStream.isClosed(),is(true));
        assertThat("pushStream is removed from parent",stream.getAssociatedStreams().contains(pushStream),is(false));
        assertThat("pushStream is removed from session",session.getStreams().containsValue(pushStream),is(false));
    }

    @Test
    public void testStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        assertThat("stream is in session",session.getStreams().containsValue(stream),is(true));
        stream.updateCloseState(true,true);
        session.onControlFrame(new SynReplyFrame(SPDY.V2,(byte)0,stream.getId(),null));
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,0),ByteBuffer.allocate(128));
        assertThat("stream is closed",stream.isClosed(),is(true));
        assertThat("stream is removed from session",session.getStreams().containsValue(stream),is(false));
    }

    @Test
    public void testStreamIsRemovedFromSessionWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        assertThat("stream is added to session",session.getStreams().containsValue(stream),is(true));
        session.rst(new RstInfo(stream.getId(),StreamStatus.CANCEL_STREAM));
        assertThat("stream is removed from session",session.getStreams().containsValue(stream),is(false));
    }

    @Test
    public void testPushStreamIsAddedAndRemovedFromSession() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get();
        assertThat("pushStream is in session",session.getStreams().containsKey(pushStream.getId()),is(true));
        session.data(pushStream,new StringDataInfo("close",true),1,TimeUnit.SECONDS,null,null);
        assertThat("pushStream is not in session",session.getStreams().containsKey(pushStream.getId()),not(true));
    }

    @Test
    public void testPushStreamIsRemovedWhenReset() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)stream.syn(new SynInfo(false)).get();
        assertThat("pushStream is in session",session.getStreams().containsKey(pushStream.getId()),is(true));
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.INVALID_STREAM));
        assertThat("pushStream is in session",session.getStreams().containsKey(pushStream.getId()),is(false));
    }

    @Test
    public void testPushStreamWithSynInfoClosedTrue() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        SynInfo synInfo = new SynInfo(headers,true,stream.getPriority());
        Stream pushStream = stream.syn(synInfo).get(1,TimeUnit.SECONDS);
        assertThat("pushStream is half closed ",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream is closed",pushStream.isClosed(),is(true));
        assertThat("stream doesn't have associated streams",stream.getAssociatedStreams().size(),is(0));
    }

    @Test
    public void testPushStreamSendHeadersWithCloseFlag() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        Stream pushStream = stream.syn(synInfo).get(1,TimeUnit.SECONDS);
        assertThat("stream is associated with pushStream",stream.getAssociatedStreams().contains(pushStream),is(true));
        pushStream.headers(new HeadersInfo(headers,true));
        assertThat("pushStream is half closed ",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream is closed",pushStream.isClosed(),is(true));
        assertThat("stream doesn't have associated streams",stream.getAssociatedStreams().size(),is(0));
    }

    @Test
    public void testStreamIsRemovedWhenHeadersWithCloseFlagAreSent() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        assertThat("stream is added to session",session.getStreams().containsValue(stream),is(true));
        stream.updateCloseState(true,false);
        stream.headers(new HeadersInfo(headers,true));
        assertThat("stream is closed",stream.isClosed(),is(true));
        assertThat("stream is removed from session",session.getStreams().containsValue(stream),is(false));
    }

    // TODO: test with headers(close true) --> make sure stream is removed from session

    @Test
    public void testListenerIsCalledForNewStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                createdListenerCalledLatch.countDown();
                super.onStreamCreated(stream);
            }
        });
        IStream stream = (IStream)createStream();
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,128),ByteBuffer.allocate(128));
        session.data(stream,new StringDataInfo("close",true),1,TimeUnit.SECONDS,null,null);
        assertThat("onStreamCreated listener has been called",createdListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForResetStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamClosed(Stream stream)
            {
                closedListenerCalledLatch.countDown();
                super.onStreamClosed(stream);
            }
        });
        IStream stream = (IStream)createStream();
        session.rst(new RstInfo(stream.getId(),StreamStatus.CANCEL_STREAM));
        assertThat("onStreamClosed listener has been called",closedListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamClosed(Stream stream)
            {
                closedListenerCalledLatch.countDown();
                super.onStreamClosed(stream);
            }
        });
        IStream stream = (IStream)createStream();
        session.onDataFrame(new DataFrame(stream.getId(),SynInfo.FLAG_CLOSE,128),ByteBuffer.allocate(128));
        session.data(stream,new StringDataInfo("close",true),1,TimeUnit.SECONDS,null,null);
        assertThat("onStreamClosed listener has been called",closedListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForNewPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch createdListenerCalledLatch = new CountDownLatch(2);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                createdListenerCalledLatch.countDown();
                super.onStreamCreated(stream);
            }
        });
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get();
        session.data(pushStream,new StringDataInfo("close",true),1,TimeUnit.SECONDS,null,null);
        assertThat("onStreamCreated listener has been called twice. Once for the stream and once for the pushStream",
                createdListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForClosedPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                closedListenerCalledLatch.countDown();
                super.onStreamCreated(stream);
            }
        });
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get();
        session.data(pushStream,new StringDataInfo("close",true),1,TimeUnit.SECONDS,null,null);
        assertThat("onStreamClosed listener has been called",closedListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
    }

    @Test
    public void testListenerIsCalledForResetPushStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        final CountDownLatch closedListenerCalledLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                closedListenerCalledLatch.countDown();
                super.onStreamCreated(stream);
            }
        });
        IStream stream = (IStream)createStream();
        IStream pushStream = (IStream)createPushStream(stream,true).get();
        session.rst(new RstInfo(pushStream.getId(),StreamStatus.CANCEL_STREAM));
        assertThat("onStreamClosed listener has been called",closedListenerCalledLatch.await(500,TimeUnit.MILLISECONDS),is(true));
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
        IStream stream = (IStream)session.syn(new SynInfo(false),new StreamFrameListener.Adapter()
        {

        }).get();
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
        }).get(1,TimeUnit.SECONDS);
        session.onControlFrame(new SynReplyFrame(SPDY.V2,SynInfo.FLAG_CLOSE,stream.getId(),headers));
        session.onDataFrame(new DataFrame(stream.getId(),(byte)0,0),ByteBuffer.allocate(128));
        assertThat("onData is never called",onDataCalledLatch.await(20,TimeUnit.MILLISECONDS),not(true));
    }
    
    private Stream createStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynInfo synInfo = new SynInfo(headers,false,(byte)0);
        return session.syn(synInfo,new StreamFrameListener.Adapter()).get(1,TimeUnit.SECONDS);
    }

    private Future<Stream> createPushStream(Stream stream, boolean addHeaders)
    {
        if (addHeaders)
        {
            headers.add("url","http://some.url/some/resource.css");
        }
        SynInfo synInfo = new SynInfo(headers,false,stream.getPriority());
        return stream.syn(synInfo);
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

}
