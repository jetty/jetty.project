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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
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
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;
import org.eclipse.jetty.spdy.frames.SynReplyFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.spdy.parser.Parser.Listener;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ClosedStreamTest extends AbstractTest
{
    //TODO: Right now it sends a rst as the stream is unknown to the session once it's closed. But according to the spec we probably should just ignore the data?!
    @Test
    public void testDataSentOnClosedStreamIsIgnored() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));

        Session session = startClient(new InetSocketAddress("localhost", server.socket().getLocalPort()), null);
        final CountDownLatch dataLatch = new CountDownLatch(2);
        session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
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

        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());

        ByteBuffer writeBuffer = generator.control(new SynReplyFrame(SPDY.V2, (byte)0, streamId, new Headers()));
        channel.write(writeBuffer);

        byte[] bytes = new byte[1];
        writeBuffer = generator.data(streamId, bytes.length, new BytesDataInfo(bytes, true));
        channel.write(writeBuffer);

        // Write again to simulate the faulty condition
        writeBuffer.flip();
        channel.write(writeBuffer);

        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        writeBuffer = generator.control(new GoAwayFrame(SPDY.V2, 0, SessionStatus.OK.getCode()));
        channel.write(writeBuffer);
        channel.shutdownOutput();
        channel.close();

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
                stream.reply(new ReplyInfo(true));
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
                    stream.data(new StringDataInfo("data send after half closed",false));
                }
                catch (RuntimeException e)
                {
                    // we expect an exception here, but we don't want it to be logged
                    exceptionWhenSendingData.countDown();
                }

                return null;
            }
        }),null);

        Stream stream = clientSession.syn(new SynInfo(false),new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyReceivedLatch.countDown();
                super.onReply(stream,replyInfo);
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                clientReceivedDataLatch.countDown();
                super.onData(stream,dataInfo);
            }
        }).get();
        assertThat("reply has been received by client",replyReceivedLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThat("stream is half closed from server",stream.isHalfClosed(),is(true));
        assertThat("client has not received any data sent after stream was half closed by server",clientReceivedDataLatch.await(100,TimeUnit.MILLISECONDS),
                is(false));
        assertThat("sending data threw an exception",exceptionWhenSendingData.await(5,TimeUnit.SECONDS),is(true));
    }

    @Test
    public void testV2ReceiveDataOnHalfClosedStream() throws Exception
    {
        final CountDownLatch clientResetReceivedLatch = runReceiveDataOnHalfClosedStream(SPDY.V2);
        assertThat("server didn't receive data",clientResetReceivedLatch.await(100,TimeUnit.MILLISECONDS),not(true));
    }
    
    @Test
    @Ignore("until v3 is properly implemented")
    public void testV3ReceiveDataOnHalfClosedStream() throws Exception
    {
        final CountDownLatch clientResetReceivedLatch = runReceiveDataOnHalfClosedStream(SPDY.V3);
        assertThat("server didn't receive data",clientResetReceivedLatch.await(100,TimeUnit.MILLISECONDS),is(true));
    }

    private CountDownLatch runReceiveDataOnHalfClosedStream(short version) throws Exception, IOException, InterruptedException
    {
        final CountDownLatch clientResetReceivedLatch = new CountDownLatch(1);
        final CountDownLatch serverReplySentLatch = new CountDownLatch(1);
        final CountDownLatch clientReplyReceivedLatch = new CountDownLatch(1);
        final CountDownLatch serverDataReceivedLatch = new CountDownLatch(1);

        InetSocketAddress startServer = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
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
                        super.onData(stream,dataInfo);
                    }
                };
            }
        });

        final SocketChannel socketChannel = SocketChannel.open(startServer);
        final Generator generator = new Generator(new StandardByteBufferPool(),new StandardCompressionFactory().newCompressor());
        ByteBuffer synData = generator.control(new SynStreamFrame(version,SynInfo.FLAG_CLOSE,1,0,(byte)0,new Headers()));

        socketChannel.write(synData);

        assertThat("server: syn reply is sent",serverReplySentLatch.await(1,TimeUnit.SECONDS),is(true));

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
                super.onControlFrame(frame);
            }

            @Override
            public void onDataFrame(DataFrame frame, ByteBuffer data)
            {
                super.onDataFrame(frame,data);
            }
        });
        ByteBuffer response = ByteBuffer.allocate(28);
        socketChannel.read(response);
        response.flip();
        parser.parse(response);

        assertThat("server didn't receive data",serverDataReceivedLatch.await(100,TimeUnit.MILLISECONDS),not(true));
        return clientResetReceivedLatch;
    }

}
