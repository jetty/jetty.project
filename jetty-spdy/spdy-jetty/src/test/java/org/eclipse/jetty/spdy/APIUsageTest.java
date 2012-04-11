package org.eclipse.jetty.spdy;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
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
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.GoAwayFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.junit.Assert;
import org.junit.Test;

public class APIUsageTest extends AbstractTest
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
                    stream.data(new StringDataInfo("failure", true));
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
        session.syn(new SynInfo(true), null);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testReceiveDataBeforeReplyIsIllegal() throws Exception
    {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));

        Session session = startClient(new InetSocketAddress("localhost", server.socket().getLocalPort()), null);
        session.syn(new SynInfo(true), null);

        SocketChannel channel = server.accept();
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        channel.read(readBuffer);
        readBuffer.flip();
        int streamId = readBuffer.getInt(8);

        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        byte[] bytes = new byte[1];
        ByteBuffer writeBuffer = generator.data(streamId, bytes.length, new BytesDataInfo(bytes, true));
        channel.write(writeBuffer);

        readBuffer.clear();
        channel.read(readBuffer);
        readBuffer.flip();
        Assert.assertEquals(ControlFrameType.RST_STREAM.getCode(), readBuffer.getShort(2));
        Assert.assertEquals(streamId, readBuffer.getInt(8));

        writeBuffer = generator.control(new GoAwayFrame(SPDY.V2, 0, SessionStatus.OK.getCode()));
        channel.write(writeBuffer);
        channel.shutdownOutput();
        channel.close();

        server.close();
    }
}
