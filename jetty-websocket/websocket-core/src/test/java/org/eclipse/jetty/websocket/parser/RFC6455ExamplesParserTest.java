package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

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
    public void testFragmentedUnmakedTextMessage()
    {
        Parser parser = new Parser();
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

        // part 2 of 2 "lo"
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
    public void testMaskedPongRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PongFrame.class,1);

        PongFrame pong = (PongFrame)capture.getFrames().get(0);
        Assert.assertThat("PongFrame.data",pong.getPayload().toString(),is("Hello"));
    }

    @Test
    public void testSingleFrameMaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        buf.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getData().toString(),is("Hello"));
    }

    @Test
    public void testSingleFrameUnmaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame unmasked text message
        buf.put(new byte[]
                { (byte)0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);

        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data", txt.getData().toString(), is("Hello"));
    }

    @Test
    public void testUnmaskedPingRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PingFrame.class,1);

        PingFrame ping = (PingFrame)capture.getFrames().get(0);
        Assert.assertThat("PingFrame.data",ping.getPayload().toString(),is("Hello"));
    }

    @Test
    public void testUnmaskedSingle256ByteBinaryMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(300);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        buf.put(new byte[]
                { (byte)0x82, 0x7E });
        buf.putShort((short)0x01_00);
        for (int i = 0; i < 256; i++)
        {
            buf.put((byte)0x44);
        }
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(BinaryFrame.class,1);

        BinaryFrame bin = (BinaryFrame)capture.getFrames().get(0);
        byte data[] = new byte[bin.getPayloadLength()];
        bin.getData().get(data,0,256);
        Assert.assertThat("BinaryFrame.data",data.length,is(256));
    }

    @Test
    public void testUnmaskedSingle64KByteBinaryMessage()
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

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(BinaryFrame.class,1);

        BinaryFrame bin = (BinaryFrame)capture.getFrames().get(0);
        byte data[] = new byte[bin.getPayloadLength()];
        bin.getData().get(data,0,dataSize);
        Assert.assertThat("BinaryFrame.data",data.length,is(dataSize));
    }

}
