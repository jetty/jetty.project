package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.SessionException;
import org.eclipse.jetty.spdy.StandardByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.junit.Assert;
import org.junit.Test;

public class UnknownControlFrameTest
{
    @Test
    public void testUnknownControlFrame() throws Exception
    {
        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, new Headers());
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory.StandardCompressor());
        ByteBuffer buffer = generator.control(frame);
        // Change the frame type to unknown
        buffer.putShort(2, (short)0);

        final CountDownLatch latch = new CountDownLatch(1);
        Parser parser = new Parser(new StandardCompressionFactory.StandardDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                latch.countDown();
            }

            @Override
            public void onDataFrame(DataFrame frame, ByteBuffer data)
            {
                latch.countDown();
            }

            @Override
            public void onStreamException(StreamException x)
            {
                latch.countDown();
            }

            @Override
            public void onSessionException(SessionException x)
            {
                latch.countDown();
            }
        });
        parser.parse(buffer);

        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }
}
