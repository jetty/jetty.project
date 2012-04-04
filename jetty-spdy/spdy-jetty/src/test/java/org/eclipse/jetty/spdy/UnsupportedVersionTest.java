package org.eclipse.jetty.spdy;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.ControlFrameType;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class UnsupportedVersionTest extends AbstractTest
{
    @Test
    public void testSynWithUnsupportedVersion() throws Exception
    {
        final CountDownLatch synLatch = new CountDownLatch(1);
        InetSocketAddress address = startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                synLatch.countDown();
                return null;
            }

            @Override
            public void onException(Throwable x)
            {
                // Suppress exception logging for this test
            }
        });

        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, new Headers());
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        ByteBuffer buffer = generator.control(frame);
        // Replace the version byte with an unsupported version
        buffer.putShort(0, (short)0x8001);

        SocketChannel channel = SocketChannel.open(address);
        channel.write(buffer);
        Assert.assertFalse(buffer.hasRemaining());

        Assert.assertFalse(synLatch.await(1, TimeUnit.SECONDS));

        buffer = ByteBuffer.allocate(1024);
        channel.read(buffer);
        buffer.flip();

        final CountDownLatch rstLatch = new CountDownLatch(1);
        Parser parser = new Parser(new StandardCompressionFactory.StandardDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                Assert.assertSame(ControlFrameType.RST_STREAM, frame.getType());
                Assert.assertEquals(StreamStatus.UNSUPPORTED_VERSION.getCode(frame.getVersion()), ((RstStreamFrame)frame).getStatusCode());
                rstLatch.countDown();
            }
        });
        parser.parse(buffer);

        Assert.assertTrue(rstLatch.await(5, TimeUnit.SECONDS));
    }
}
