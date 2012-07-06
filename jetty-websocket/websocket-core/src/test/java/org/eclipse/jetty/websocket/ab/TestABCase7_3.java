package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase7_3
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    @Test
    public void testCase7_3_1GenerateEmptyClose()
    {
        WebSocketFrame closeFrame = FrameBuilder.close().asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, closeFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x00 });

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_1ParseEmptyClose()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(0));

    }


    @Test (expected = WebSocketException.class)
    public void testCase7_3_2Generate1BytePayloadClose()
    {
        WebSocketFrame closeFrame = FrameBuilder.close().payload(new byte[]
                { 0x00 }).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, closeFrame);
    }

    @Test
    public void testCase7_3_2Parse1BytePayloadClose()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88, 0x01, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        Assert.assertEquals("error on invalid close payload",1,capture.getErrorCount(ProtocolException.class));

        ProtocolException known = (ProtocolException)capture.getErrors().get(0);

        Assert.assertThat("Payload.message",known.getMessage(),containsString("Invalid close frame payload length"));
    }

    @Test
    public void testCase7_3_3GenerateCloseWithStatus()
    {
        WebSocketFrame closeFrame = FrameBuilder.close(1000).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, closeFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8 });

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_3ParseCloseWithStatus()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8  });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(2));

    }


    @Test
    public void testCase7_3_4GenerateCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        WebSocketFrame closeFrame = FrameBuilder.close(1000,message.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, closeFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88 });

        byte b = 0x00; // no masking
        b |= (message.length() + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_4ParseCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);
        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(messageBytes.length + 2));

    }


    @Test
    public void testCase7_3_5GenerateCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        WebSocketFrame closeFrame = FrameBuilder.close(1000,message.toString()).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(132);
        generator.generate(actual, closeFrame);

        ByteBuffer expected = ByteBuffer.allocate(132);


        expected.put(new byte[]
                { (byte)0x88 });

        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_5ParseCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        ByteBuffer expected = ByteBuffer.allocate(132);

        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking

        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);
        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(125));

    }

    @Test(expected = ProtocolException.class)
    public void testCase7_3_6GenerateCloseWithInvalidStatusReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 124 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        WebSocketFrame closeFrame = FrameBuilder.close().asFrame();

        ByteBuffer bb = ByteBuffer.allocate(WebSocketFrame.MAX_CONTROL_PAYLOAD + 1); // 126 which is too big for control

        bb.putChar((char)1000);
        bb.put(messageBytes);

        BufferUtil.flipToFlush(bb,0);

        closeFrame.setPayload(BufferUtil.toArray(bb));

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual,closeFrame);
    }

    @Test
    public void testCase7_3_6ParseCloseWithInvalidStatusReason()
    {
        byte[] messageBytes = new byte[124];
        Arrays.fill(messageBytes,(byte)'*');

        ByteBuffer expected = ByteBuffer.allocate(256);

        byte b;

        // fin + op
        b = 0x00;
        b |= 0x80; // fin on
        b |= 0x08; // close
        expected.put(b);

        // mask + len
        b = 0x00;
        b |= 0x00; // no masking
        b |= 0x7E; // 2 byte len
        expected.put(b);

        // 2 byte len
        expected.putChar((char)(messageBytes.length + 2));

        // payload
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // reason

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        Assert.assertEquals("error on invalid close payload",1,capture.getErrorCount(ProtocolException.class));

        ProtocolException known = (ProtocolException)capture.getErrors().get(0);

        Assert.assertThat("Payload.message",known.getMessage(),containsString("Invalid control frame payload length"));
    }
}
