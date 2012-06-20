package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Assert;
import org.junit.Test;

/**
 * Collection of Example packets as found in <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
 */
public class RFC6455ExamplesParserTest
{
    @Test
    public void testFragmentedUnmaskedTextMessage()
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);

        ByteBuffer buf = ByteBuffer.allocate(16);

        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A fragmented unmasked text message (part 1 of 2 "Hel")
        buf.put(new byte[]
                { (byte)0x01, (byte)0x03, 0x48, (byte)0x65, 0x6c });
        buf.flip();

        // Parse #1
        parser.parse(buf);

        // part 2 of 2 "lo" (A continuation frame of the prior text message)
        buf.flip();
        buf.put(new byte[]
                { (byte)0x80, 0x02, 0x6c, 0x6f });
        buf.flip();

        // Parse #2
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,2);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame[0].data",txt.getData().toString(),is("Hel"));
        txt = (TextFrame)capture.getFrames().get(1);
        Assert.assertThat("TextFrame[1].data",txt.getData().toString(),is("lo"));
    }

    @Test
    public void testSingleMaskedPongRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PongFrame.class,1);

        PongFrame pong = (PongFrame)capture.getFrames().get(0);
        ByteBufferAssert.assertEquals("PongFrame.payload","Hello",pong.getPayload());
    }

    @Test
    public void testSingleMaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        buf.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getData().toString(),is("Hello"));
    }

    @Test
    public void testSingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;

        ByteBuffer buf = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        buf.put(new byte[]
                { (byte)0x82, 0x7E });
        buf.putShort((short)0x01_00);
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x44);
        }
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(BinaryFrame.class,1);

        BinaryFrame bin = (BinaryFrame)capture.getFrames().get(0);
        bin.getData().flip();

        Assert.assertThat("BinaryFrame.payloadLength",bin.getPayloadLength(),is(dataSize));
        ByteBufferAssert.assertSize("BinaryFrame.payload",dataSize,bin.getData());

        ByteBuffer data = bin.getData();
        for (int i = dataSize; i > 0; i--)
        {
            Assert.assertThat("BinaryFrame.data[" + i + "]",data.get(),is((byte)0x44));
        }
    }

    @Test
    public void testSingleUnmasked64KByteBinaryMessage()
    {
        int dataSize = 1024 * 64;

        ByteBuffer buf = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64 Kbytes binary message in a single unmasked frame
        buf.put(new byte[]
                { (byte)0x82, 0x7F });
        buf.putInt(dataSize);
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x77);
        }
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(BinaryFrame.class,1);

        BinaryFrame bin = (BinaryFrame)capture.getFrames().get(0);
        bin.getData().flip();

        Assert.assertThat("BinaryFrame.payloadLength",bin.getPayloadLength(),is(dataSize));
        ByteBufferAssert.assertSize("BinaryFrame.payload",dataSize,bin.getData());

        ByteBuffer data = bin.getData();
        for (int i = dataSize; i > 0; i--)
        {
            Assert.assertThat("BinaryFrame.data[" + i + "]",data.get(),is((byte)0x77));
        }
    }

    @Test
    public void testSingleUnmaskedPingRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PingFrame.class,1);

        PingFrame ping = (PingFrame)capture.getFrames().get(0);
        ByteBufferAssert.assertEquals("PingFrame.payload","Hello",ping.getPayload());
    }

    @Test
    public void testSingleUnmaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame unmasked text message
        buf.put(new byte[]
                { (byte)0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data", txt.getData().toString(), is("Hello"));
    }
}
