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


package org.eclipse.jetty.spdy.server;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.PushInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.spdy.parser.Parser.Listener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Test;

public class PushStreamTest extends AbstractTest
{
    private static final Logger LOG = Log.getLogger(PushStreamTest.class);

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
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.push(new PushInfo(new Fields(), true), new Promise.Adapter<Stream>());
                return null;
            }
        }), null);

        Stream stream = clientSession.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                assertThat("streamId is even", stream.getId() % 2, is(0));
                assertThat("stream is unidirectional", stream.isUnidirectional(), is(true));
                assertThat("stream is closed", stream.isClosed(), is(true));
                assertThat("stream has associated stream", stream.getAssociatedStream(), notNullValue());
                try
                {
                    stream.reply(new ReplyInfo(false), new Callback.Adapter());
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
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                try
                {
                    replyBarrier.await(5, TimeUnit.SECONDS);
                    return new StreamFrameListener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataInfo dataInfo)
                        {
                            try
                            {
                                if (dataInfo.isClose())
                                {
                                    stream.data(new StringDataInfo("close stream", true));
                                    closeBarrier.await(5, TimeUnit.SECONDS);
                                }
                                streamDataSent.countDown();
                                if (pushStreamDataReceived.getCount() == 2)
                                {
                                    Stream pushStream = stream.push(new PushInfo(new Fields(), false));
                                    streamExchanger.exchange(pushStream, 5, TimeUnit.SECONDS);
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

        }), null);

        Stream stream = clientSession.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                pushStreamSynLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        pushStreamDataReceived.countDown();
                        super.onData(stream, dataInfo);
                    }
                };
            }

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                try
                {
                    replyBarrier.await(5, TimeUnit.SECONDS);
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
                    closeBarrier.await(5, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                }
            }
        });

        replyBarrier.await(5, TimeUnit.SECONDS);
        stream.data(new StringDataInfo("client data", false));
        Stream pushStream = streamExchanger.exchange(null, 5, TimeUnit.SECONDS);
        pushStream.data(new StringDataInfo("first push data frame", false));
        // nasty, but less complex than using another cyclicBarrier for example
        while (pushStreamDataReceived.getCount() != 1)
            Thread.sleep(1);
        stream.data(new StringDataInfo("client close", true));
        closeBarrier.await(5, TimeUnit.SECONDS);
        assertThat("stream is closed", stream.isClosed(), is(true));
        pushStream.data(new StringDataInfo("second push data frame while associated stream has been closed already", false));
        assertThat("2 pushStream data frames have been received.", pushStreamDataReceived.await(5, TimeUnit.SECONDS), is(true));
        assertThat("2 data frames have been sent", streamDataSent.await(5, TimeUnit.SECONDS), is(true));
        assertThatNoExceptionOccurred(exceptionCountDownLatch);
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
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                stream.push(new PushInfo(1, TimeUnit.SECONDS, new Fields(), false),
                        new Promise.Adapter<Stream>()
                        {
                            @Override
                            public void failed(Throwable x)
                            {
                                pushStreamFailedLatch.countDown();
                            }
                        });
                return super.onSyn(stream, synInfo);
            }
        }), new SessionFrameListener.Adapter());

        clientSession.syn(new SynInfo(new Fields(), true), null);
        assertThat("pushStream push has failed", pushStreamFailedLatch.await(5, TimeUnit.SECONDS), is(true));
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
                    Stream pushStream = stream.push(new PushInfo(new Fields(), false));
                    stream.reply(new ReplyInfo(true));
                    // wait until stream is closed
                    streamClosedLatch.await(5, TimeUnit.SECONDS);
                    pushStream.data(new BytesDataInfo(transferBytes, true), new Callback.Adapter());
                    return null;
                }
                catch (Exception e)
                {
                    exceptionCountDownLatch.countDown();
                    throw new IllegalStateException(e);
                }
            }
        }), null);

        Stream stream = clientSession.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
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
                                exchanger.exchange(receivedBytes.slice(), 5, TimeUnit.SECONDS);
                            }
                            catch (Exception e)
                            {
                                exceptionCountDownLatch.countDown();
                            }
                        }
                    }
                };
            }

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                streamClosedLatch.countDown();
                super.onReply(stream, replyInfo);
            }
        });

        ByteBuffer receivedBytes = exchanger.exchange(null, 5, TimeUnit.SECONDS);

        assertThat("received byte array is the same as transferred byte array", Arrays.equals(transferBytes, receivedBytes.array()), is(true));
        assertThat("onReply has been called to close the stream", streamClosedLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("stream is closed", stream.isClosed(), is(true));
        assertThat("all data has been received", allDataReceived.await(20, TimeUnit.SECONDS), is(true));
        assertThatNoExceptionOccurred(exceptionCountDownLatch);
    }

    private byte[] createHugeByteArray(int sizeInBytes)
    {
        byte[] bytes = new byte[sizeInBytes];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }


    @Test
    public void testClientResetsStreamAfterPushSynDoesPreventSendingDataFramesWithFlowControl() throws Exception
    {
        final boolean flowControl = true;
        testNoMoreFramesAreSentOnPushStreamAfterClientResetsThePushStream(flowControl);
    }

    @Test
    public void testClientResetsStreamAfterPushSynDoesPreventSendingDataFramesWithoutFlowControl() throws Exception
    {
        final boolean flowControl = false;
        testNoMoreFramesAreSentOnPushStreamAfterClientResetsThePushStream(flowControl);
    }

    private volatile boolean read = true;

    private void testNoMoreFramesAreSentOnPushStreamAfterClientResetsThePushStream(final boolean flowControl) throws Exception
    {
        final short version = SPDY.V3;
        final AtomicBoolean unexpectedExceptionOccurred = new AtomicBoolean(false);
        final CountDownLatch resetReceivedLatch = new CountDownLatch(1);
        final CountDownLatch allDataFramesReceivedLatch = new CountDownLatch(1);
        final CountDownLatch goAwayReceivedLatch = new CountDownLatch(1);
        final int dataSizeInBytes = 1024 * 256;
        final byte[] transferBytes = createHugeByteArray(dataSizeInBytes);

        InetSocketAddress serverAddress = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
            {
                new Thread(new Runnable()
                {

                    @Override
                    public void run()
                    {
                        Stream pushStream = null;
                        try
                        {
                            stream.reply(new ReplyInfo(false), new Callback.Adapter());
                            pushStream = stream.push(new PushInfo(new Fields(), false));
                            resetReceivedLatch.await(5, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException | ExecutionException | TimeoutException e)
                        {
                            e.printStackTrace();
                            unexpectedExceptionOccurred.set(true);
                        }
                        assert pushStream != null;
                        try
                        {
                            pushStream.data(new BytesDataInfo(transferBytes, true));
                            stream.data(new StringDataInfo("close", true));
                        }
                        catch (InterruptedException | ExecutionException | TimeoutException e)
                        {
                            LOG.debug(e.getMessage());
                        }
                    }
                }).start();
                return null;
            }

            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                resetReceivedLatch.countDown();
            }

            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                goAwayReceivedLatch.countDown();
            }
        }/*TODO, flowControl*/);

        final SocketChannel channel = SocketChannel.open(serverAddress);
        final Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        int streamId = 1;
        ByteBuffer writeBuffer = generator.control(new SynStreamFrame(version, (byte)0, streamId, 0, (byte)0, (short)0, new Fields()));
        channel.write(writeBuffer);
        assertThat("writeBuffer is fully written", writeBuffer.hasRemaining(), is(false));

        final Parser parser = new Parser(new StandardCompressionFactory.StandardDecompressor());
        parser.addListener(new Listener.Adapter()
        {
            int bytesRead = 0;

            @Override
            public void onControlFrame(ControlFrame frame)
            {
                if (frame instanceof SynStreamFrame)
                {
                    int pushStreamId = ((SynStreamFrame)frame).getStreamId();
                    ByteBuffer writeBuffer = generator.control(new RstStreamFrame(version, pushStreamId, StreamStatus.CANCEL_STREAM.getCode(version)));
                    try
                    {
                        channel.write(writeBuffer);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                        unexpectedExceptionOccurred.set(true);
                    }
                }
            }

            @Override
            public void onDataFrame(DataFrame frame, ByteBuffer data)
            {
                if (frame.getStreamId() == 2)
                    bytesRead = bytesRead + frame.getLength();
                if (bytesRead == dataSizeInBytes)
                {
                    allDataFramesReceivedLatch.countDown();
                    return;
                }
                if (flowControl)
                {
                    ByteBuffer writeBuffer = generator.control(new WindowUpdateFrame(version, frame.getStreamId(), frame.getLength()));
                    try
                    {
                        channel.write(writeBuffer);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                        unexpectedExceptionOccurred.set(true);
                    }
                }
            }
        });

        Thread reader = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                ByteBuffer readBuffer = ByteBuffer.allocate(dataSizeInBytes * 2);
                while (read)
                {
                    try
                    {
                        channel.read(readBuffer);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                        unexpectedExceptionOccurred.set(true);
                    }
                    readBuffer.flip();
                    parser.parse(readBuffer);
                    readBuffer.clear();
                }

            }
        });
        reader.start();
        read = false;

        assertThat("no unexpected exceptions occurred", unexpectedExceptionOccurred.get(), is(false));
        assertThat("not all dataframes have been received as the pushstream has been reset by the client.", allDataFramesReceivedLatch.await(streamId, TimeUnit.SECONDS), is(false));


        ByteBuffer buffer = generator.control(new GoAwayFrame(version, streamId, SessionStatus.OK.getCode()));
        channel.write(buffer);
        Assert.assertThat(buffer.hasRemaining(), is(false));

        assertThat("GoAway frame is received by server", goAwayReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
        channel.shutdownOutput();
        channel.close();
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
                stream.push(new PushInfo(new Fields(), false), new Promise.Adapter<Stream>());
                return null;
            }
        }), null);

        Stream stream = clientSession.syn(new SynInfo(new Fields(), false),
                new VerifyPushStreamIdIsEvenStreamFrameListener(pushStreamIdIsEvenLatch));
        Stream stream2 = clientSession.syn(new SynInfo(new Fields(), false),
                new VerifyPushStreamIdIsEvenStreamFrameListener(pushStreamIdIsEvenLatch));
        Stream stream3 = clientSession.syn(new SynInfo(new Fields(), false),
                new VerifyPushStreamIdIsEvenStreamFrameListener(pushStreamIdIsEvenLatch));
        assertStreamIdIsOdd(stream);
        assertStreamIdIsOdd(stream2);
        assertStreamIdIsOdd(stream3);

        assertThat("all pushStreams had even ids", pushStreamIdIsEvenLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private class VerifyPushStreamIdIsEvenStreamFrameListener extends StreamFrameListener.Adapter
    {
        final CountDownLatch pushStreamIdIsEvenLatch;

        private VerifyPushStreamIdIsEvenStreamFrameListener(CountDownLatch pushStreamIdIsEvenLatch)
        {
            this.pushStreamIdIsEvenLatch = pushStreamIdIsEvenLatch;
        }

        @Override
        public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
        {
            assertStreamIdIsEven(stream);
            pushStreamIdIsEvenLatch.countDown();
            return super.onPush(stream, pushInfo);
        }
    }

    private void assertStreamIdIsEven(Stream stream)
    {
        assertThat("streamId is odd", stream.getId() % 2, is(0));
    }

    private void assertStreamIdIsOdd(Stream stream)
    {
        assertThat("streamId is odd", stream.getId() % 2, is(1));
    }

    private void assertThatNoExceptionOccurred(final CountDownLatch exceptionCountDownLatch) throws InterruptedException
    {
        assertThat("No exception occurred", exceptionCountDownLatch.await(1, TimeUnit.SECONDS), is(false));
    }
}
