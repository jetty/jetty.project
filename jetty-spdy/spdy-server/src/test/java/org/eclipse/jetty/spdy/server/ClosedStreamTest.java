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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
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
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.spdy.parser.Parser.Listener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ClosedStreamTest extends AbstractTest
{
    //TODO: Right now it sends a rst as the stream is unknown to the session once it's closed.
    //TODO: But according to the spec we probably should just ignore the data?!
    @Test
    public void testDataSentOnClosedStreamIsIgnored() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));

        Session session = startClient(new InetSocketAddress("localhost", server.socket().getLocalPort()), null);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
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
        Assert.assertThat(writeBuffer.hasRemaining(), is(false));

        byte[] bytes = new byte[1];
        writeBuffer = generator.data(streamId, bytes.length, new BytesDataInfo(bytes, true));
        channel.write(writeBuffer);
        Assert.assertThat(writeBuffer.hasRemaining(), is(false));

        // Write again to simulate the faulty condition
        writeBuffer.flip();
        channel.write(writeBuffer);
        Assert.assertThat(writeBuffer.hasRemaining(), is(false));

        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));

        server.close();
    }

    @Test
    public void testSendDataOnHalfClosedStreamCausesExceptionOnServer() throws Exception
    {
        final CountDownLatch replyReceivedLatch = new CountDownLatch(1);
        final CountDownLatch clientReceivedDataLatch = new CountDownLatch(1);
        final CountDownLatch exceptionWhenSendingData = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true), new Callback.Adapter());
                try
                {
                    replyReceivedLatch.await(5,TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                try
                {
                    stream.data(new StringDataInfo("data send after half closed",false), new Callback.Adapter());
                }
                catch (RuntimeException e)
                {
                    // we expect an exception here, but we don't want it to be logged
                    exceptionWhenSendingData.countDown();
                }

                return null;
            }
        }),null);

        Stream stream = clientSession.syn(new SynInfo(new Fields(), false),new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyReceivedLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                clientReceivedDataLatch.countDown();
            }
        });
        assertThat("reply has been received by client",replyReceivedLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThat("stream is half closed from server",stream.isHalfClosed(),is(true));
        assertThat("client has not received any data sent after stream was half closed by server",
                clientReceivedDataLatch.await(1,TimeUnit.SECONDS), is(false));
        assertThat("sending data threw an exception",exceptionWhenSendingData.await(5,TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testV2ReceiveDataOnHalfClosedStream() throws Exception
    {
        runReceiveDataOnHalfClosedStream(SPDY.V2);
    }

    @Test
    @Ignore("until v3 is properly implemented")
    public void testV3ReceiveDataOnHalfClosedStream() throws Exception
    {
        runReceiveDataOnHalfClosedStream(SPDY.V3);
    }

    private void runReceiveDataOnHalfClosedStream(short version) throws Exception
    {
        final CountDownLatch clientResetReceivedLatch = new CountDownLatch(1);
        final CountDownLatch serverReplySentLatch = new CountDownLatch(1);
        final CountDownLatch clientReplyReceivedLatch = new CountDownLatch(1);
        final CountDownLatch serverDataReceivedLatch = new CountDownLatch(1);
        final CountDownLatch goAwayReceivedLatch = new CountDownLatch(1);

        InetSocketAddress startServer = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                serverReplySentLatch.countDown();
                try
                {
                    clientReplyReceivedLatch.await(5,TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        serverDataReceivedLatch.countDown();
                    }
                };
            }
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayInfo)
            {
                goAwayReceivedLatch.countDown();
            }
        });

        final Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        int streamId = 1;
        ByteBuffer synData = generator.control(new SynStreamFrame(version,SynInfo.FLAG_CLOSE, streamId,0,(byte)0,(short)0,new Fields()));

        final SocketChannel socketChannel = SocketChannel.open(startServer);
        socketChannel.write(synData);
        assertThat("synData is fully written", synData.hasRemaining(), is(false));

        assertThat("server: push reply is sent",serverReplySentLatch.await(5,TimeUnit.SECONDS),is(true));

        Parser parser = new Parser(new StandardCompressionFactory.StandardDecompressor());
        parser.addListener(new Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                if (frame instanceof SynReplyFrame)
                {
                    SynReplyFrame synReplyFrame = (SynReplyFrame)frame;
                    clientReplyReceivedLatch.countDown();
                    int streamId = synReplyFrame.getStreamId();
                    ByteBuffer data = generator.data(streamId,0,new StringDataInfo("data",false));
                    try
                    {
                        socketChannel.write(data);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
                else if (frame instanceof RstStreamFrame)
                {
                    clientResetReceivedLatch.countDown();
                }
            }
        });
        ByteBuffer response = ByteBuffer.allocate(28);
        socketChannel.read(response);
        response.flip();
        parser.parse(response);

        assertThat("server didn't receive data",serverDataReceivedLatch.await(1,TimeUnit.SECONDS),not(true));
        assertThat("client didn't receive reset",clientResetReceivedLatch.await(1,TimeUnit.SECONDS),not(true));

        ByteBuffer buffer = generator.control(new GoAwayFrame(version, streamId, SessionStatus.OK.getCode()));
        socketChannel.write(buffer);
        Assert.assertThat(buffer.hasRemaining(), is(false));

        assertThat("GoAway frame is received by server", goAwayReceivedLatch.await(5,TimeUnit.SECONDS), is(true));

        socketChannel.close();
    }
}
