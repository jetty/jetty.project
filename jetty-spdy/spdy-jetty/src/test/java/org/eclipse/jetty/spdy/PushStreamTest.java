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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.charset.Charset;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Test;

public class PushStreamTest extends AbstractTest
{
    @Test
    public void testSynPushStream() throws Exception
    {
        final CountDownLatch pushStreamSynLatch = new CountDownLatch(1);

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
                pushStreamSynLatch.countDown();
                return super.onSyn(stream,synInfo);
            }
        });

        clientSession.syn(new SynInfo(false),null).get();
        assertThat("onSyn has been called",pushStreamSynLatch.await(5,TimeUnit.SECONDS),is(true));
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

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                try
                {
                    replyBarrier.await(1,TimeUnit.SECONDS);
                }
                catch (InterruptedException | BrokenBarrierException | TimeoutException e1)
                {
                    e1.printStackTrace();
                }
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
                                closeBarrier.await(1,TimeUnit.SECONDS);
                            }
                            streamDataSent.countDown();
                            if (pushStreamDataReceived.getCount() == 2)
                            {
                                Stream pushStream = stream.syn(new SynInfo(false)).get();
                                streamExchanger.exchange(pushStream,100,TimeUnit.SECONDS);
                            }
                        }
                        catch (InterruptedException | ExecutionException | TimeoutException | BrokenBarrierException e)
                        {
                            e.printStackTrace();
                        }
                        super.onData(stream,dataInfo);
                    }
                };
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
                    replyBarrier.await(100,TimeUnit.SECONDS);
                }
                catch (InterruptedException | BrokenBarrierException | TimeoutException e)
                {
                    e.printStackTrace();
                }
                super.onReply(stream,replyInfo);
            }
            
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                try
                {
                    closeBarrier.await(100,TimeUnit.SECONDS);
                }
                catch (InterruptedException | BrokenBarrierException | TimeoutException e)
                {
                    e.printStackTrace();
                }
                super.onData(stream,dataInfo);
            }
        }).get();
        replyBarrier.await(100,TimeUnit.SECONDS);
        stream.data(new StringDataInfo("data",false));
        Stream pushStream = streamExchanger.exchange(null,1,TimeUnit.SECONDS);
        pushStream.data(new StringDataInfo("first push data frame",false));
        while (pushStreamDataReceived.getCount() != 1)  //nasty, but less complex than using another cyclicBarrier for example
            Thread.sleep(1);
        stream.data(new StringDataInfo("client close",true));
        closeBarrier.await(1,TimeUnit.SECONDS);
        assertThat("stream is closed",stream.isClosed(),is(true));
        pushStream.data(new StringDataInfo("second push data frame while associated stream has been closed already",false));
        assertThat("2 pushStream data frames have been received.",pushStreamDataReceived.await(1,TimeUnit.SECONDS),is(true));
        assertThat("2 data frames have been sent",streamDataSent.await(1,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testSynPushStreamOnClosedStream() throws Exception
    {
        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch pushStreamFailedLatch = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Session serverSession = stream.getSession();
                serverSession.rst(new RstInfo(stream.getId(),StreamStatus.REFUSED_STREAM));
                synLatch.countDown();
                stream.syn(new SynInfo(false),1,TimeUnit.SECONDS,new Handler<Stream>()
                {
                    @Override
                    public void completed(Stream context)
                    {
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        pushStreamFailedLatch.countDown();
                    }
                });
                return super.onSyn(stream,synInfo);
            }
        }),new SessionFrameListener.Adapter());

        clientSession.syn(new SynInfo(false),null);
        assertThat("onSyn has been called",synLatch.await(1,TimeUnit.SECONDS),is(true));
        assertThat("pushStream syn has failed",pushStreamFailedLatch.await(1,TimeUnit.SECONDS),is(true));
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
}
