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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
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
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
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
                            stream.getSession().settings(new SettingsInfo(settings));
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

        Stream stream = session.syn(new SynInfo(false), null).get(5, TimeUnit.SECONDS);
        stream.data(new BytesDataInfo(new byte[size * 2], false));
        settingsLatch.await(5, TimeUnit.SECONDS);

        // Send the second chunk of data, must not arrive since we're flow control stalled now
        stream.data(new BytesDataInfo(new byte[size * 2], true));
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
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                stream.data(new BytesDataInfo(new byte[length], true));
                return null;
            }
        }), null);

        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
        session.settings(new SettingsInfo(settings));

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final Exchanger<DataInfo> exchanger = new Exchanger<>();
        session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
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
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
        Assert.assertEquals(windowSize, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
        Assert.assertEquals(0, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.consume(dataInfo.length());

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
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
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                Settings settings = new Settings();
                settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
                session.settings(new SettingsInfo(settings));
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
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

        Stream stream = session.syn(new SynInfo(true), null).get(5, TimeUnit.SECONDS);
        final int length = 5 * windowSize;
        stream.data(new BytesDataInfo(new byte[length], true));

        DataInfo dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
        Assert.assertEquals(windowSize, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
        Assert.assertEquals(0, dataInfo.available());
        Assert.assertEquals(0, dataInfo.consumed());
        dataInfo.consume(dataInfo.length());

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        // Check that we are flow control stalled
        expectException(TimeoutException.class, new Callable<DataInfo>()
        {
            @Override
            public DataInfo call() throws Exception
            {
                return exchanger.exchange(null, 1, TimeUnit.SECONDS);
            }
        });
        Assert.assertEquals(dataInfo.length() / 2, dataInfo.consumed());
        dataInfo.asByteBuffer(true);

        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
        // Check that we are not flow control stalled
        dataInfo = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        Assert.assertEquals(dataInfo.length(), dataInfo.consumed());
    }

    private void expectException(Class<? extends Exception> exception, Callable command)
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
