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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Test;

public class ProtocolViolationsTest extends AbstractTest
{
    @Test
    public void testSendDataBeforeReplyIsIllegal() throws Exception
    {
        final CountDownLatch resetLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    stream.data(new StringDataInfo("failure", true), new Callback.Adapter());
                    return null;
                }
                catch (IllegalStateException x)
                {
                    latch.countDown();
                    return null;
                }
            }
        }), new SessionFrameListener.Adapter()
        {
            @Override
            public void onRst(Session session, RstInfo rstInfo)
            {
                Assert.assertSame(StreamStatus.PROTOCOL_ERROR, rstInfo.getStreamStatus());
                resetLatch.countDown();
            }
        });
        session.syn(new SynInfo(new Fields(), true), null);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testReceiveDataBeforeReplyIsIllegal() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));

        Session session = startClient(new InetSocketAddress("localhost", server.socket().getLocalPort()), null);
        session.syn(new SynInfo(new Fields(), true), null);

        SocketChannel channel = server.accept();
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        channel.read(readBuffer);
        readBuffer.flip();
        int streamId = readBuffer.getInt(8);

        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        byte[] bytes = new byte[1];
        ByteBuffer writeBuffer = generator.data(streamId, bytes.length, new BytesDataInfo(bytes, true));
        channel.write(writeBuffer);
        assertThat("data is fully written", writeBuffer.hasRemaining(),is(false));

        readBuffer.clear();
        channel.read(readBuffer);
        readBuffer.flip();
        Assert.assertEquals(ControlFrameType.RST_STREAM.getCode(), readBuffer.getShort(2));
        Assert.assertEquals(streamId, readBuffer.getInt(8));

        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));

        server.close();
    }

    @Test(expected = IllegalStateException.class)
    public void testSendDataAfterCloseIsIllegal() throws Exception
    {
        Session session = startClient(startServer(null), null);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), null);
        stream.data(new StringDataInfo("test", true));
    }

    @Test(expected = IllegalStateException.class)
    public void testSendHeadersAfterCloseIsIllegal() throws Exception
    {
        Session session = startClient(startServer(null), null);
        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), null);
        stream.headers(new HeadersInfo(new Fields(), true));
    }

    @Test //TODO: throws an ISException in StandardStream.updateCloseState(). But instead we should send a rst or something to the server probably?!
    public void testServerClosesStreamTwice() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));

        Session session = startClient(new InetSocketAddress("localhost", server.socket().getLocalPort()), null);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataLatch.countDown();
            }
        });

        SocketChannel channel = server.accept();
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        channel.read(readBuffer);
        readBuffer.flip();
        int streamId = readBuffer.getInt(8);

        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory.StandardCompressor());

        ByteBuffer writeBuffer = generator.control(new SynReplyFrame(SPDY.V2, (byte)0, streamId, new Fields()));
        channel.write(writeBuffer);
        assertThat("SynReply is fully written", writeBuffer.hasRemaining(), is(false));

        byte[] bytes = new byte[1];
        writeBuffer = generator.data(streamId, bytes.length, new BytesDataInfo(bytes, true));
        channel.write(writeBuffer);
        assertThat("data is fully written", writeBuffer.hasRemaining(), is(false));

        // Write again to simulate the faulty condition
        writeBuffer.flip();
        channel.write(writeBuffer);
        assertThat("data is fully written", writeBuffer.hasRemaining(), is(false));

        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));

        server.close();
    }
}
