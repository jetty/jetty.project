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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class PushStreamTest extends AbstractTest
{
    @Test
    public void testSynPushStream() throws Exception
    {
        final AtomicReference<Stream> pushStreamRef = new AtomicReference<>();
        final CountDownLatch pushStreamLatch = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                stream.syn(new SynInfo(true));
                return null;
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                assertThat("streamId is even", stream.getId() % 2, is(0));
                assertThat("stream is unidirectional", stream.isUnidirectional(), is(true));
                assertThat("stream is closed", stream.isClosed(), is(true));
                assertThat("stream has associated stream", stream.getAssociatedStream(), notNullValue());
                try
                {
                    stream.reply(new ReplyInfo(false));
                    fail("Cannot reply to push streams");
                }
                catch (IllegalStateException x)
                {
                    // Expected
                }
                pushStreamRef.set(stream);
                pushStreamLatch.countDown();
                return null;
            }
        });

        Stream stream = clientSession.syn(new SynInfo(true), null).get();
        assertThat("onSyn has been called", pushStreamLatch.await(5, TimeUnit.SECONDS), is(true));
        Stream pushStream = pushStreamRef.get();
        assertThat("main stream and associated stream are the same", stream, sameInstance(pushStream.getAssociatedStream()));
    }

    @Test
    public void testSendDataOnPushStreamAfterAssociatedStreamIsClosed() throws Exception
    {
        final Exchanger<Stream> streamExchanger = new Exchanger<>();
        final CountDownLatch pushStreamSynLatch = new CountDownLatch(1);
        final CyclicBarrier replyBarrier = new CyclicBarrier(3);
        final CyclicBarrier closeBarrier = new CyclicBarrier(3);
        final CountDownLatch streamDataSent = new CountDownLatch(2);
        final CountDownLatch pushStreamDataReceived = new CountDownLatch(2);
        final CountDownLatch exceptionCountDownLatch = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                try
                {
                    replyBarrier.await(5,TimeUnit.SECONDS);
                    return new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            try
                            {
                                if (dataInfo.isClose())
                                {
                                    stream.data(new StringDataInfo("close stream",true));
                                    closeBarrier.await(5,TimeUnit.SECONDS);
                                }
                                streamDataSent.countDown();
                                if (pushStreamDataReceived.getCount() == 2)
                                {
                                    Stream pushStream = stream.syn(new SynInfo(false)).get();
                                    streamExchanger.exchange(pushStream,5,TimeUnit.SECONDS);
                                }
                            }
                            catch (Exception e)
                            {
                                exceptionCountDownLatch.countDown();
                            }
                        }
                    };
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                    throw new IllegalStateException(e);
                }
            }

        }),new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                pushStreamSynLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        pushStreamDataReceived.countDown();
                        super.onData(stream,dataInfo);
                    }
                };
            }
        });

        Stream stream = clientSession.syn(new SynInfo(false),new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                try
                {
                    replyBarrier.await(5,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                }
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                try
                {
                    closeBarrier.await(5,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                }
            }
        }).get();

        replyBarrier.await(5,TimeUnit.SECONDS);
        stream.data(new StringDataInfo("client data",false));
        Stream pushStream = streamExchanger.exchange(null,5,TimeUnit.SECONDS);
        pushStream.data(new StringDataInfo("first push data frame",false));
        // nasty, but less complex than using another cyclicBarrier for example
        while (pushStreamDataReceived.getCount() != 1)
            Thread.sleep(1);
        stream.data(new StringDataInfo("client close",true));
        closeBarrier.await(5,TimeUnit.SECONDS);
        assertThat("stream is closed",stream.isClosed(),is(true));
        pushStream.data(new StringDataInfo("second push data frame while associated stream has been closed already",false));
        assertThat("2 pushStream data frames have been received.",pushStreamDataReceived.await(5,TimeUnit.SECONDS),is(true));
        assertThat("2 data frames have been sent",streamDataSent.await(5,TimeUnit.SECONDS),is(true));
        assertThatNoExceptionOccured(exceptionCountDownLatch);
    }

    @Test
    public void testSynPushStreamOnClosedStream() throws Exception
    {
        final CountDownLatch pushStreamFailedLatch = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                stream.syn(new SynInfo(false),1,TimeUnit.SECONDS,new Callback.Adapter<Stream>()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        pushStreamFailedLatch.countDown();
                    }
                });
                return super.onSyn(stream,synInfo);
            }
        }),new SessionFrameListener.Adapter());

        clientSession.syn(new SynInfo(true),null);
        assertThat("pushStream syn has failed",pushStreamFailedLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testSendBigDataOnPushStreamWhenAssociatedStreamIsClosed() throws Exception
    {
        final CountDownLatch streamClosedLatch = new CountDownLatch(1);
        final CountDownLatch allDataReceived = new CountDownLatch(1);
        final CountDownLatch exceptionCountDownLatch = new CountDownLatch(1);
        final Exchanger<ByteBuffer> exchanger = new Exchanger<>();
        final int dataSizeInBytes = 1024 * 1024 * 1;
        final byte[] transferBytes = createHugeByteArray(dataSizeInBytes);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    Stream pushStream = stream.syn(new SynInfo(false)).get();
                    stream.reply(new ReplyInfo(true));
                    // wait until stream is closed
                    streamClosedLatch.await(5,TimeUnit.SECONDS);
                    pushStream.data(new BytesDataInfo(transferBytes,true));
                    return null;
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                    throw new IllegalStateException(e);
                }
            }
        }),new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                return new StreamFrameListener.Adapter()
                {
                    ByteBuffer receivedBytes = ByteBuffer.allocate(dataSizeInBytes);

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consumeInto(receivedBytes);
                        if (dataInfo.isClose())
                        {
                            allDataReceived.countDown();
                            try
                            {
                                receivedBytes.flip();
                                exchanger.exchange(receivedBytes.slice(),5,TimeUnit.SECONDS);
                            }
                            catch (Exception e)
                            {
                                exceptionCountDownLatch.countDown();
                            }
                        }
                    }
                };
            }
        });

        Stream stream = clientSession.syn(new SynInfo(true),new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                streamClosedLatch.countDown();
                super.onReply(stream,replyInfo);
            }
        }).get();

        ByteBuffer receivedBytes = exchanger.exchange(null,5,TimeUnit.SECONDS);

        assertThat("received byte array is the same as transferred byte array",Arrays.equals(transferBytes,receivedBytes.array()),is(true));
        assertThat("onReply has been called to close the stream",streamClosedLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThat("stream is closed",stream.isClosed(),is(true));
        assertThat("all data has been received",allDataReceived.await(20,TimeUnit.SECONDS),is(true));
        assertThatNoExceptionOccured(exceptionCountDownLatch);
    }

    private byte[] createHugeByteArray(int sizeInBytes)
    {
        byte[] bytes = new byte[sizeInBytes];
        new Random().nextBytes(bytes);
        return bytes;
    }

    @Test
    public void testOddEvenStreamIds() throws Exception
    {
        final CountDownLatch pushStreamIdIsEvenLatch = new CountDownLatch(3);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.syn(new SynInfo(false));
                return null;
            }
        }),new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                assertStreamIdIsEven(stream);
                pushStreamIdIsEvenLatch.countDown();
                return super.onSyn(stream,synInfo);
            }
        });

        Stream stream = clientSession.syn(new SynInfo(false),null).get();
        Stream stream2 = clientSession.syn(new SynInfo(false),null).get();
        Stream stream3 = clientSession.syn(new SynInfo(false),null).get();
        assertStreamIdIsOdd(stream);
        assertStreamIdIsOdd(stream2);
        assertStreamIdIsOdd(stream3);

        assertThat("all pushStreams had even ids",pushStreamIdIsEvenLatch.await(5,TimeUnit.SECONDS),is(true));
    }

    private void assertStreamIdIsEven(Stream stream)
    {
        assertThat("streamId is odd",stream.getId() % 2,is(0));
    }

    private void assertStreamIdIsOdd(Stream stream)
    {
        assertThat("streamId is odd",stream.getId() % 2,is(1));
    }

    private void assertThatNoExceptionOccured(final CountDownLatch exceptionCountDownLatch) throws InterruptedException
    {
        assertThat("No exception occured", exceptionCountDownLatch.await(1,TimeUnit.SECONDS),is(false));
    }
}
