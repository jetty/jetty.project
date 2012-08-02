package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.junit.Assert;
import org.junit.Test;

public class GeneratorTest
{

    private void parsePartial(Parser parser, ByteBuffer buf, int numBytes)
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        parser.parse(ByteBuffer.wrap(arr));
    }

    /**
     * Prevent regression of masking of many packets.
     */
    @Test
    public void testManyMasked()
    {
        byte[] MASK =
            { 0x11, 0x22, 0x33, 0x44 };
        int pingCount = 10;

        // the generator
        Generator generator = new UnitGenerator();

        // Prepare frames
        List<WebSocketFrame> send = new ArrayList<>();
        for (int i = 0; i < pingCount; i++)
        {
            String payload = String.format("ping-%d[%X]",i,i);
            send.add(WebSocketFrame.ping().setPayload(payload));
        }
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());

        // Generate into single bytebuffer
        int buflen = 0;
        for (WebSocketFrame f : send)
        {
            buflen += f.getPayloadLength() + Generator.OVERHEAD;
        }
        ByteBuffer completeBuf = ByteBuffer.allocate(buflen);
        BufferUtil.clearToFill(completeBuf);

        // Generate frames
        for (WebSocketFrame f : send)
        {
            f.setMask(MASK); // make sure we have mask set
            ByteBuffer slice = f.getPayload().slice();
            BufferUtil.put(generator.generate(f),completeBuf);
            f.setPayload(slice);
        }
        BufferUtil.flipToFlush(completeBuf,0);

        // Parse complete buffer (5 bytes at a time)
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        int segmentSize = 5;
        while (completeBuf.remaining() > 0)
        {
            parsePartial(parser,completeBuf,segmentSize);
        }

        capture.dump();

        // Assert validity of frame
        int frameCount = send.size();
        capture.assertFrameCount(frameCount);
        for (int i = 0; i < frameCount; i++)
        {
            WebSocketFrame actual = capture.getFrames().get(i);
            WebSocketFrame expected = send.get(i);
            String prefix = "Frame[" + i + "]";
            Assert.assertThat(prefix + ".opcode",actual.getOpCode(),is(expected.getOpCode()));
            Assert.assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.getPayloadLength()));
            ByteBufferAssert.assertEquals(prefix + ".payload",expected.getPayload(),actual.getPayload());
        }
    }

    /**
     * Test the windowed generate of a frame that has no masking.
     */
    @Test
    public void testWindowedGenerate()
    {
        // A decent sized frame, no masking
        byte payload[] = new byte[10240];
        Arrays.fill(payload,(byte)0x44);

        WebSocketFrame frame = WebSocketFrame.binary(payload);

        // tracking values
        int totalParts = 0;
        int totalBytes = 0;
        int windowSize = 1024;
        int expectedHeaderSize = 4;
        int expectedParts = (int)Math.ceil((double)(payload.length + expectedHeaderSize) / windowSize);

        // the generator
        Generator generator = new UnitGenerator();

        // lets see how many parts the generator makes
        boolean done = false;
        while (!done)
        {
            // sanity check in loop, our test should fail if this is true.
            Assert.assertThat("Too many parts",totalParts,lessThanOrEqualTo(expectedParts));

            ByteBuffer buf = generator.generate(windowSize,frame);
            Assert.assertThat("Generated should not exceed window size",buf.remaining(),lessThanOrEqualTo(windowSize));

            totalBytes += buf.remaining();
            totalParts++;

            done = (frame.remaining() <= 0);
        }

        // validate
        Assert.assertThat("Created Parts",totalParts,is(expectedParts));
        Assert.assertThat("Created Bytes",totalBytes,is(payload.length + expectedHeaderSize));
    }

    @Test
    public void testWindowedGenerateWithMasking()
    {
        // A decent sized frame, with masking
        byte payload[] = new byte[10240];
        Arrays.fill(payload,(byte)0x55);

        byte mask[] = new byte[]
                { 0x2A, (byte)0xF0, 0x0F, 0x00 };

        WebSocketFrame frame = WebSocketFrame.binary(payload);
        frame.setMask(mask); // masking!

        // tracking values
        int totalParts = 0;
        int totalBytes = 0;
        int windowSize = 2929; // important for test, use an odd # window size to test masking across window barriers
        int expectedHeaderSize = 8;
        int expectedParts = (int)Math.ceil((double)(payload.length + expectedHeaderSize) / windowSize);

        // Buffer to capture generated bytes (we do this to validate that the masking
        // is working correctly
        ByteBuffer completeBuf = ByteBuffer.allocate(payload.length + expectedHeaderSize);
        BufferUtil.clearToFill(completeBuf);

        // Generate and capture generator output
        Generator generator = new UnitGenerator();

        boolean done = false;
        while (!done)
        {
            // sanity check in loop, our test should fail if this is true.
            Assert.assertThat("Too many parts",totalParts,lessThanOrEqualTo(expectedParts));

            ByteBuffer buf = generator.generate(windowSize,frame);
            Assert.assertThat("Generated should not exceed window size",buf.remaining(),lessThanOrEqualTo(windowSize));

            totalBytes += buf.remaining();
            totalParts++;

            BufferUtil.put(buf,completeBuf);

            done = (frame.remaining() <= 0);
        }

        Assert.assertThat("Created Parts",totalParts,is(expectedParts));
        Assert.assertThat("Created Bytes",totalBytes,is(payload.length + expectedHeaderSize));

        // Parse complete buffer.
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        BufferUtil.flipToFlush(completeBuf,0);
        parser.parse(completeBuf);

        // Assert validity of frame
        WebSocketFrame actual = capture.getFrames().get(0);
        Assert.assertThat("Frame.opcode",actual.getOpCode(),is(OpCode.BINARY));
        Assert.assertThat("Frame.payloadLength",actual.getPayloadLength(),is(payload.length));

        // Validate payload contents for proper masking
        ByteBuffer actualData = actual.getPayload().slice();
        Assert.assertThat("Frame.payload.remaining",actualData.remaining(),is(payload.length));
        while (actualData.remaining() > 0)
        {
            Assert.assertThat("Actual.payload[" + actualData.position() + "]",actualData.get(),is((byte)0x55));
        }
    }
}
