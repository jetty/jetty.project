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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class SynReplyTest extends AbstractTest
{
    @Test
    public void testSynReply() throws Exception
    {
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        final CountDownLatch sessionLatch = new CountDownLatch(1);
        final CountDownLatch synLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                sessionRef.set(session);
                sessionLatch.countDown();
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isHalfClosed());
                stream.reply(new ReplyInfo(new Fields(), true), new Callback.Adapter());
                synLatch.countDown();
                return null;
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        Assert.assertTrue(sessionLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = sessionRef.get();
        Assert.assertNotNull(serverSession);

        final CountDownLatch streamCreatedLatch = new CountDownLatch(1);
        final CountDownLatch streamRemovedLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener()
        {
            @Override
            public void onStreamCreated(Stream stream)
            {
                streamCreatedLatch.countDown();
            }

            @Override
            public void onStreamClosed(Stream stream)
            {
                streamRemovedLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0),
                new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(stream.isClosed());
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(streamCreatedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(stream.isClosed());

        Assert.assertTrue(streamRemovedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testSynDataReply() throws Exception
    {
        final byte[] dataBytes = "foo".getBytes(StandardCharsets.UTF_8);

        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertFalse(stream.isHalfClosed());
                Assert.assertFalse(stream.isClosed());
                synLatch.countDown();
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ByteBuffer buffer = ByteBuffer.allocate(2);
                        while (dataInfo.available() > 0)
                        {
                            dataInfo.readInto(buffer);
                            buffer.flip();
                            bytes.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                            buffer.clear();
                        }
                        Assert.assertTrue(Arrays.equals(dataBytes, bytes.toByteArray()));
                        Assert.assertTrue(stream.isHalfClosed());
                        Assert.assertFalse(stream.isClosed());

                        stream.reply(new ReplyInfo(true), new Callback.Adapter());
                        Assert.assertTrue(stream.isClosed());
                        dataLatch.countDown();
                    }
                };
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch streamRemovedLatch = new CountDownLatch(1);
        session.addListener(new Session.StreamListener.Adapter()
        {
            @Override
            public void onStreamClosed(Stream stream)
            {
                streamRemovedLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0),
                new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyLatch.countDown();
            }
        });
        stream.data(new BytesDataInfo(dataBytes, true));

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(streamRemovedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testSynReplyDataData() throws Exception
    {
        final String data1 = "foo";
        final String data2 = "bar";
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(final Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isHalfClosed());

                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new StringDataInfo(5, TimeUnit.SECONDS, data1, false), new Callback.Adapter()
                {
                    @Override
                    public void succeeded()
                    {
                        stream.data(new StringDataInfo(data2, true), new Adapter());
                    }
                });

                return null;
            }
        }), null);

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch1 = new CountDownLatch(1);
        final CountDownLatch dataLatch2 = new CountDownLatch(1);
        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            private AtomicInteger dataCount = new AtomicInteger();

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int dataCount = this.dataCount.incrementAndGet();
                if (dataCount == 1)
                {
                    String chunk1 = dataInfo.asString(StandardCharsets.UTF_8, true);
                    Assert.assertEquals(data1, chunk1);
                    dataLatch1.countDown();
                }
                else if (dataCount == 2)
                {
                    String chunk2 = dataInfo.asString(StandardCharsets.UTF_8, true);
                    Assert.assertEquals(data2, chunk2);
                    dataLatch2.countDown();
                }
            }
        });

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch2.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSynDataReplyData() throws Exception
    {
        final String serverData = "server";
        final String clientData = "client";

        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch clientDataLatch = new CountDownLatch(1);
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        replyLatch.countDown();
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        String data = dataInfo.asString(StandardCharsets.UTF_8, true);
                        Assert.assertEquals(clientData, data);
                        clientDataLatch.countDown();
                    }
                }, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream stream)
                    {
                        stream.data(new StringDataInfo(serverData, true), new Callback.Adapter());
                    }
                });
            }
        };

        final CountDownLatch synLatch = new CountDownLatch(1);
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        SessionFrameListener clientSessionFrameListener = new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertEquals(0, stream.getId() % 2);

                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new StringDataInfo(clientData, true), new Callback.Adapter());
                synLatch.countDown();

                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        ByteBuffer buffer = dataInfo.asByteBuffer(false);
                        String data = StandardCharsets.UTF_8.decode(buffer).toString();
                        Assert.assertEquals(serverData, data);
                        serverDataLatch.countDown();
                    }
                };
            }
        };

        startClient(startServer(serverSessionFrameListener), clientSessionFrameListener);

        Assert.assertTrue(synLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSynReplyDataSynReplyData() throws Exception
    {
        final String data = "foo";
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isHalfClosed());

                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new StringDataInfo(data, true), new Callback.Adapter());

                return null;
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch replyLatch = new CountDownLatch(2);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        StreamFrameListener clientStreamFrameListener = new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                String chunk = dataInfo.asString(StandardCharsets.UTF_8, true);
                Assert.assertEquals(data, chunk);
                dataLatch.countDown();
            }
        };
        session.syn(new SynInfo(new Fields(), true), clientStreamFrameListener);
        session.syn(new SynInfo(new Fields(), true), clientStreamFrameListener);

        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }
}
