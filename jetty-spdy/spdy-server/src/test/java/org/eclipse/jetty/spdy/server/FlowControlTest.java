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
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.Assert;
import org.junit.Test;

public class FlowControlTest extends AbstractTest
{
    @Test
    public void testFlowControlWithConcurrentSettings() throws Exception
    {
        // Initial window is 64 KiB. We allow the client to send 1024 B
        // then we change the window to 512 B. At this point, the client
        // must stop sending data (although the initial window allows it)

        final int size = 512;
        final AtomicReference<DataInfo> dataInfoRef = new AtomicReference<>();
        final CountDownLatch dataLatch = new CountDownLatch(2);
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = startClient(SPDY.V3, startServer(SPDY.V3, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                return new StreamFrameListener.Adapter()
                {
                    private final AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        int dataFrameCount = dataFrames.incrementAndGet();
                        if (dataFrameCount == 1)
                        {
                            dataInfoRef.set(dataInfo);
                            Settings settings = new Settings();
                            settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, size));
                            stream.getSession().settings(new SettingsInfo(settings), new FutureCallback());
                        }
                        else if (dataFrameCount > 1)
                        {
                            dataInfo.consume(dataInfo.length());
                            dataLatch.countDown();
                        }
                    }
                };
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }
        });

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        stream.data(new BytesDataInfo(new byte[size * 2], false));
        settingsLatch.await(5, TimeUnit.SECONDS);

        // Send the second chunk of data, must not arrive since we're flow control stalled now
        stream.data(new BytesDataInfo(new byte[size * 2], true), new Callback.Adapter());
        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        // Consume the data arrived to server, this will resume flow control
        DataInfo dataInfo = dataInfoRef.get();
        dataInfo.consume(dataInfo.length());

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final int length = 5 * windowSize;
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = startClient(SPDY.V3, startServer(SPDY.V3, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new BytesDataInfo(new byte[length], true), new Callback.Adapter());
                return null;
            }
        }), null);

        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
        session.settings(new SettingsInfo(settings));

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final Exchanger<DataInfo> exchanger = new Exchanger<>();
        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            private AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                try
                {
                    int dataFrames = this.dataFrames.incrementAndGet();
                    if (dataFrames == 1)
                    {
                        // Do not consume nor read from the data frame.
                        // We should then be flow-control stalled
                        exchanger.exchange(dataInfo);
                    }
                    else if (dataFrames == 2)
                    {
                        // Read but not consume, we should be flow-control stalled
                        dataInfo.asByteBuffer(false);
                        exchanger.exchange(dataInfo);
                    }
                    else if (dataFrames == 3)
                    {
                        // Consume partially, we should be flow-control stalled
                        dataInfo.consumeInto(ByteBuffer.allocate(dataInfo.length() / 2));
                        exchanger.exchange(dataInfo);
                    }
                    else if (dataFrames == 4 || dataFrames == 5)
                    {
                        // Consume totally
                        dataInfo.asByteBuffer(true);
                        exchanger.exchange(dataInfo);
                    }
                    else
                    {
                        Assert.fail();
                    }
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        });

        DataInfo dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(windowSize, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(0, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.consume(dataInfo.length());

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(dataInfo.length() / 2, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
        // Check that we are not flow control stalled
        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
    }

    @Test
    public void testClientFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final Exchanger<DataInfo> exchanger = new Exchanger<>();
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = startClient(SPDY.V3, startServer(SPDY.V3, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                Settings settings = new Settings();
                settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
                session.settings(new SettingsInfo(settings), new FutureCallback());
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                return new StreamFrameListener.Adapter()
                {
                    private AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        try
                        {
                            int dataFrames = this.dataFrames.incrementAndGet();
                            if (dataFrames == 1)
                            {
                                // Do not consume nor read from the data frame.
                                // We should then be flow-control stalled
                                exchanger.exchange(dataInfo);
                            }
                            else if (dataFrames == 2)
                            {
                                // Read but not consume, we should be flow-control stalled
                                dataInfo.asByteBuffer(false);
                                exchanger.exchange(dataInfo);
                            }
                            else if (dataFrames == 3)
                            {
                                // Consume partially, we should be flow-control stalled
                                dataInfo.consumeInto(ByteBuffer.allocate(dataInfo.length() / 2));
                                exchanger.exchange(dataInfo);
                            }
                            else if (dataFrames == 4 || dataFrames == 5)
                            {
                                // Consume totally
                                dataInfo.asByteBuffer(true);
                                exchanger.exchange(dataInfo);
                            }
                            else
                            {
                                Assert.fail();
                            }
                        }
                        catch (InterruptedException x)
                        {
                            throw new SPDYException(x);
                        }
                    }
                };
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }
        });

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0), null);
        final int length = 5 * windowSize;
        stream.data(new BytesDataInfo(new byte[length], true), new Callback.Adapter());

        DataInfo dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(windowSize, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(0, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.consume(dataInfo.length());

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        Assert.assertEquals(dataInfo.length() / 2, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
        // Check that we are not flow control stalled
        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
    }

    @Test
    public void testStreamsStalledDoesNotStallOtherStreams() throws Exception
    {
        final int windowSize = 1024;
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = startClient(SPDY.V3, startServer(SPDY.V3, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new BytesDataInfo(new byte[windowSize * 2], true), new Callback.Adapter());
                return null;
            }
        }), null);
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
        session.settings(new SettingsInfo(settings));

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicReference<DataInfo> dataInfoRef1 = new AtomicReference<>();
        final AtomicReference<DataInfo> dataInfoRef2 = new AtomicReference<>();
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int frames = dataFrames.incrementAndGet();
                if (frames == 1)
                {
                    // Do not consume it to stall flow control
                    dataInfoRef1.set(dataInfo);
                }
                else
                {
                    dataInfo.consume(dataInfo.length());
                    if (dataInfo.isClose())
                        latch.countDown();
                }
            }
        });
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int frames = dataFrames.incrementAndGet();
                if (frames == 1)
                {
                    // Do not consume it to stall flow control
                    dataInfoRef2.set(dataInfo);
                }
                else
                {
                    dataInfo.consume(dataInfo.length());
                    if (dataInfo.isClose())
                        latch.countDown();
                }
            }
        });
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                DataInfo dataInfo1 = dataInfoRef1.getAndSet(null);
                if (dataInfo1 != null)
                    dataInfo1.consume(dataInfo1.length());
                DataInfo dataInfo2 = dataInfoRef2.getAndSet(null);
                if (dataInfo2 != null)
                    dataInfo2.consume(dataInfo2.length());
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendBigFileWithoutFlowControl() throws Exception
    {
        testSendBigFile(SPDY.V2);
    }

    @Test
    public void testSendBigFileWithFlowControl() throws Exception
    {
        testSendBigFile(SPDY.V3);
    }

    private void testSendBigFile(short version) throws Exception
    {
        final int dataSize = 1024 * 1024;
        final ByteBufferDataInfo bigByteBufferDataInfo = new ByteBufferDataInfo(ByteBuffer.allocate(dataSize),false);
        final CountDownLatch allDataReceivedLatch = new CountDownLatch(1);

        Session session = startClient(version, startServer(version, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(bigByteBufferDataInfo, new Callback.Adapter());
                return null;
            }
        }),new SessionFrameListener.Adapter());

        session.syn(new SynInfo(new Fields(), false),new StreamFrameListener.Adapter()
        {
            private int dataBytesReceived;

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataBytesReceived = dataBytesReceived + dataInfo.length();
                dataInfo.consume(dataInfo.length());
                if (dataBytesReceived == dataSize)
                    allDataReceivedLatch.countDown();
            }
        });

        assertThat("all data bytes have been received by the client", allDataReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private void checkThatWeAreFlowControlStalled(final Exchanger<DataInfo> exchanger)
    {
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
    }

    private void expectException(Class<? extends Exception> exception, Callable<DataInfo> command)
    {
        try
        {
            command.call();
            Assert.fail();
        }
        catch (Exception x)
        {
            Assert.assertSame(exception, x.getClass());
        }
    }
}
