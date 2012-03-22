package org.eclipse.jetty.spdy;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.spdy.api.AbstractSynInfo;
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
        Stream pushStream = createPushStream(stream).get();
        assertThat("Push stream must be associated to the first stream created", pushStream.getParentStream().getId(), is(stream.getId()));
        assertThat("streamIds need to be monotonic",pushStream.getId(), greaterThan(stream.getId()));
    }

    private Stream createStream() throws InterruptedException, ExecutionException
    {
        AbstractSynInfo synInfo = new SynInfo(headers,false,(byte)0);
        return session.syn(synInfo,streamFrameListener).get();
    }
    
    private Future<Stream> createPushStream(Stream stream)
    {
        headers.add("url","http://some.url");
        return stream.synPushStream(headers, false, stream.getPriority());
    }
    
    @Test
    public void testPushStreamIsClosedWhenAssociatedStreamIsClosed() throws InterruptedException, ExecutionException{
        
        Stream stream = createStream();
        Stream pushStream = createPushStream(stream).get();
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
