package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

/**
 * Text Message Spec testing the {@link Generator} and {@link Parser}
 */
public class TestABCase1_1
{
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

    @Test
    public void testGenerate125ByteTextCase1_1_2()
    {
        int length = 125;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerate126ByteTextCase1_1_3()
    {
        int length = 126;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        // expected.put((byte)((length>>8) & 0xFF));
        // expected.put((byte)(length & 0xFF));
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerate127ByteTextCase1_1_4()
    {
        int length = 127;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        // expected.put((byte)((length>>8) & 0xFF));
        // expected.put((byte)(length & 0xFF));
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerate128ByteTextCase1_1_5()
    {
        int length = 128;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);

        expected.put((byte)(length >> 8));
        expected.put((byte)(length & 0xFF));
        // expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerate65535ByteTextCase1_1_6()
    {
        int length = 65535;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
                { (byte)0xff, (byte)0xff });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerate65536ByteTextCase1_1_7()
    {
        int length = 65536;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = FrameBuilder.text(builder.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(length + 16);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
                { 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00 });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testGenerateEmptyTextCase1_1_1()
    {
        WebSocketFrame textFrame = FrameBuilder.text("").asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(10);
        generator.generate(actual,textFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

    }

    @Test
    public void testParse125ByteTextCase1_1_2()
    {
        int length = 125;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse126ByteTextCase1_1_3()
    {
        int length = 126;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse127ByteTextCase1_1_4()
    {
        int length = 127;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse128ByteTextCase1_1_5()
    {
        int length = 128;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse65535ByteTextCase1_1_6()
    {
        // Debug.enableDebugLogging(Parser.class);
        // Debug.enableDebugLogging(TextPayloadParser.class);

        int length = 65535;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
                { (byte)0xff, (byte)0xff });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMaxTextMessageSize(length);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse65536ByteTextCase1_1_7()
    {
        int length = 65536;

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
                { 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00 });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMaxTextMessageSize(length);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParseEmptyTextCase1_1_1()
    {

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(0));
    }
}
