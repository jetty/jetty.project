package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.Assert;
import org.junit.Test;

public class ResetGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        Generator generator = new Generator(byteBufferPool);

        int streamId = 13;
        int error = 17;

        // Iterate a few times to be sure generator and parser are properly reset.
        final List<ResetFrame> frames = new ArrayList<>();
        for (int i = 0; i < 2; ++i)
        {
            Generator.Result result = generator.generateReset(streamId, error);
            Parser parser = new Parser(new Parser.Listener.Adapter()
            {
                @Override
                public boolean onResetFrame(ResetFrame frame)
                {
                    frames.add(frame);
                    return false;
                }
            });

            frames.clear();
            for (ByteBuffer buffer : result.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }
        }

        Assert.assertEquals(1, frames.size());
        ResetFrame frame = frames.get(0);
        Assert.assertEquals(streamId, frame.getStreamId());
        Assert.assertEquals(error, frame.getError());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        Generator generator = new Generator(byteBufferPool);

        int streamId = 13;
        int error = 17;

        final List<ResetFrame> frames = new ArrayList<>();
        Generator.Result result = generator.generateReset(streamId, error);
        Parser parser = new Parser(new Parser.Listener.Adapter()
        {
            @Override
            public boolean onResetFrame(ResetFrame frame)
            {
                frames.add(frame);
                return false;
            }
        });

        for (ByteBuffer buffer : result.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        Assert.assertEquals(1, frames.size());
        ResetFrame frame = frames.get(0);
        Assert.assertEquals(streamId, frame.getStreamId());
        Assert.assertEquals(error, frame.getError());
    }
}
