package org.eclipse.jetty.spdy;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.generator.Generator;
import org.junit.Before;
import org.junit.Test;

public class StandardSessionTest
{

    private ByteBufferPool bufferPool;
    private Executor threadPool;
    private StandardSession session;
    private Generator generator;
    private ScheduledExecutorService scheduler;
    private Headers headers;
    private StreamFrameListener.Adapter streamFrameListener;

    @Before
    public void setUp() throws Exception
    {
        bufferPool = new StandardByteBufferPool();
        threadPool = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        generator = new Generator(new StandardByteBufferPool(),new StandardCompressionFactory.StandardCompressor());
        session = new StandardSession(SPDY.V2,bufferPool,threadPool,scheduler,new TestController(),null,1,null,generator);
        headers = new Headers();
        streamFrameListener = new StreamFrameListener.Adapter();
    }

    @Test
    public void testServerPush() throws InterruptedException, ExecutionException
    {
        Stream stream = createStream();
        PushStream pushStream = createPushStream(stream);
        assertThat("Push stream must be associated to the first stream created", pushStream.getAssociatedStream().getId(), is(stream.getId()));
        assertThat(pushStream.getId(), greaterThan(stream.getId()));
    }

    private Stream createStream() throws InterruptedException, ExecutionException
    {
        SynInfo synInfo = new SynInfo(headers,false,false,0,(byte)0);
        return session.syn(synInfo,streamFrameListener).get();
    }
    
    private PushStream createPushStream(Stream stream)
    {
        headers.add("url","http://some.url");
        return (PushStream)stream.synPushStream(headers, stream.getPriority());
    }
    
    @Test
    public void testPushStreamIsClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException{
        
        Stream stream = createStream();
        PushStream pushStream = createPushStream(stream);
        assertThat("stream is not halfClosed", stream.isHalfClosed(), is(false));
        assertThat("stream is not closed", stream.isClosed(), is(false));
        assertThat("pushStream is not halfClosed", pushStream.isHalfClosed(), is(false));
        assertThat("pushStream is not closed", pushStream.isClosed(), is(false));
        
        ReplyInfo replyInfo = new ReplyInfo(true);
        stream.reply(replyInfo);
        assertThat("stream is halfClosed", stream.isHalfClosed(), is(true));
        assertThat("stream is not closed", stream.isClosed(), is(false));
        assertThat("pushStream is halfClosed", pushStream.isHalfClosed(), is(true));
        assertThat("pushStream is not closed", pushStream.isClosed(), is(false));
        
        stream.reply(replyInfo);
        assertThat("stream is closed", stream.isClosed(), is(true));
        assertThat("pushStream is closed", pushStream.isClosed(), is(true));
        
    }

    /**
     * is a stream is closed, it'll get removed from StandardSession. So this test is equal to testUnidirectionalStreamWithInvalidAssociatedStreamId and serves
     * mainly for documentational purposes. However if the handling of closed streams change to NOT remove closed streams from StandardSession this test needs
     * to be adapted for sure.
     */
    @Test(expected = IllegalStateException.class)
    public void testUnidirectionalStreamWithClosedAssociatedStreamId() throws InterruptedException, ExecutionException
    {
        createUnidirectionalStreamWithMissingAssociatedStream();
    }

    @Test(expected = IllegalStateException.class)
    public void testUnidirectionalStreamWithInvalidAssociatedStreamId() throws InterruptedException, ExecutionException
    {
        createUnidirectionalStreamWithMissingAssociatedStream();
    }

    private void createUnidirectionalStreamWithMissingAssociatedStream() throws InterruptedException, ExecutionException
    {
        headers.add("url","http://some.url");
        SynInfo synInfo = new SynInfo(headers,false,true,0,(byte)0);
        session.syn(synInfo,streamFrameListener).get();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnidirectionalStreamWithMissingUrlHeader() throws InterruptedException, ExecutionException
    {
        Stream stream = createStream();
        SynInfo synInfo = new SynInfo(headers,false,true,stream.getId(),(byte)0);
        session.syn(synInfo,streamFrameListener);
    }

    // TODO: remove duplication in AsyncTimeoutTest
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
