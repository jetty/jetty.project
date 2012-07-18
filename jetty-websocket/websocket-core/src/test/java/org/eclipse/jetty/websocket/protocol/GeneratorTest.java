package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

public class GeneratorTest
{
    @Test
    public void testWindowedGenerate()
    {
        byte payload[] = new byte[10240];
        Arrays.fill(payload,(byte)0x44);

        WebSocketFrame frame = WebSocketFrame.binary(payload);

        int totalParts = 0;
        int totalBytes = 0;
        int windowSize = 1024;
        int expectedHeaderSize = 4;
        int expectedParts = (int)Math.ceil((double)(payload.length + expectedHeaderSize) / windowSize);

        Generator generator = new UnitGenerator();

        boolean done = false;
        while (!done)
        {
            Assert.assertThat("Too many parts",totalParts,lessThan(20));

            ByteBuffer buf = generator.generate(windowSize,frame);
            // System.out.printf("Generated buf.limit() = %,d%n",buf.limit());

            totalBytes += buf.remaining();
            totalParts++;

            done = (frame.remaining() <= 0);
        }

        Assert.assertThat("Created Parts",totalParts,is(expectedParts));
        Assert.assertThat("Created Bytes",totalBytes,is(payload.length + expectedHeaderSize));
    }
}
