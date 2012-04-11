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
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
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

    @Mock private ISession sessionMock;
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
        Stream stream = createStream();
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("stream should not be halfClosed",stream.isHalfClosed(),is(false));
        assertThat("stream should not be closed",stream.isClosed(),is(false));
        assertThat("pushStream expected to be halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream expected to not be closed",pushStream.isClosed(),is(false));

        ReplyInfo replyInfo = new ReplyInfo(true);
        stream.reply(replyInfo).get(1,TimeUnit.SECONDS);
        assertThat("stream should be halfClosed",stream.isHalfClosed(),is(true));
        assertThat("stream should not be closed",stream.isClosed(),is(false));
        assertThat("pushStream should be halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream should not be closed",pushStream.isClosed(),is(false));

        stream.reply(replyInfo).get(1,TimeUnit.SECONDS);
        assertThat("stream should be closed",stream.isClosed(),is(true));
        assertThat("pushStream should be closed",pushStream.isClosed(),is(false));
    }

    @Test
    public void testCreatePushStreamOnClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        stream.updateCloseState(true);
        assertThat("stream should be halfClosed",stream.isHalfClosed(),is(true));
        stream.updateCloseState(true);
        assertThat("stream should be closed",stream.isClosed(),is(true));
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
        assertThat("PushStream creation failed",failedLatch.getCount(),equalTo(0L));
    }

    @Test
    public void testPushStreamIsAddedToParent() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("PushStream has not been added to parent",stream.getAssociatedStreams().contains(pushStream),is(true));
    }

    @Test
    public void testPushStreamIsRemovedFromParentWhenClosed() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        Stream pushStream = createPushStream(stream,true).get(1,TimeUnit.SECONDS);
        assertThat("pushStream expected to be halfClosed",pushStream.isHalfClosed(),is(true));
        assertThat("PushStream has not been added to parent",stream.getAssociatedStreams().contains(pushStream),is(true));
        ReplyInfo replyInfo = new ReplyInfo(true);
        pushStream.reply(replyInfo);
        assertThat("pushStream expected to be halfClosed",pushStream.isHalfClosed(),is(true));
        pushStream.reply(replyInfo);
        assertThat("pushStream expected to be closed",pushStream.isClosed(),is(true));
        assertThat("PushStream expected to be removed from parent",stream.getAssociatedStreams().contains(pushStream),is(false));
    }

    //TODO: Does it make any sense to open a pushstream with synInfo close=true?
    @Test
    public void testPushStreamWithSynInfoClosedTrue() throws InterruptedException, ExecutionException, TimeoutException
    {
        IStream stream = (IStream)createStream();
        SynInfo synInfo = new SynInfo(headers,true,stream.getPriority());
        Stream pushStream = stream.syn(synInfo).get(1,TimeUnit.SECONDS);
        assertThat("pushStream expected to be half closed",pushStream.isHalfClosed(),is(true));
        assertThat("pushStream expected to be not closed",pushStream.isClosed(),is(true));
    }

    @SuppressWarnings("unchecked")
    @Test
    @Ignore("need to implement better handling of half closed connections: 376201")
    public void testSendDataOnHalfClosedStream() throws InterruptedException, ExecutionException, TimeoutException
    {
        SynStreamFrame synStreamFrame = new SynStreamFrame(SPDY.V2,(byte)1,1,0 ,(byte)0,null);
        Stream stream = new StandardStream(synStreamFrame,sessionMock,8184,null);
        assertThat("stream is half closed",stream.isHalfClosed(),is(true));
        stream.data(new StringDataInfo("data on half closed stream",true));
        verify(sessionMock, never()).data(any(IStream.class),any(DataInfo.class),anyInt(),any(TimeUnit.class),any(Handler.class),any(void.class));
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
