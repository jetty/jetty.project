package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase7_3
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    @Test (expected = WebSocketException.class)
    public void testGenerate1BytePayloadCloseCase7_3_2()
    {
        CloseFrame closeFrame = new CloseFrame();
        closeFrame.setPayload(new byte[] {0x00});

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, closeFrame);
    }

    @Test
    public void testGenerateCloseWithStatusCase7_3_3()
    {
        CloseFrame closeFrame = new CloseFrame(1000);

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
    public void testGenerateCloseWithStatusMaxReasonCase7_3_5()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        CloseFrame closeFrame = new CloseFrame(1000, message.toString());

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

    @Test (expected = IllegalArgumentException.class )
    public void testGenerateCloseWithStatusMaxReasonCase7_3_6()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 124 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        CloseFrame closeFrame = new CloseFrame(1000, message.toString());

    }

    @Test
    public void testGenerateCloseWithStatusReasonCase7_3_4()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        CloseFrame closeFrame = new CloseFrame(1000, message);

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
    public void testGenerateEmptyCloseCase7_3_1()
    {
        CloseFrame closeFrame = new CloseFrame();

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
    public void testParse1BytePayloadCloseCase7_3_2()
    {
        Debug.enableDebugLogging(Parser.class);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88, 0x01, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on invalid close payload", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("invalid payload should be in message",known.getMessage().contains("invalid payload length"));
    }

    @Test
    public void testParseCloseWithStatusCase7_3_3()
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
        capture.assertHasFrame(CloseFrame.class,1);

        CloseFrame pActual = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(2));
        ByteBufferAssert.assertSize("CloseFrame.payload",2,pActual.getPayload());

    }


    @Test
    public void testParseCloseWithStatusMaxReasonCase7_3_5()
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
        capture.assertHasFrame(CloseFrame.class,1);

        CloseFrame pActual = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(125));
        ByteBufferAssert.assertSize("CloseFrame.payload", 125,pActual.getPayload());

    }

    @Test
    public void testParseCloseWithStatusMaxReasonCase7_3_6()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 124 ; ++i )
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

        Assert.assertEquals( "error on invalid close payload", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("invalid payload should be in message",known.getMessage().contains("invalid payload length"));
    }

    @Test
    public void testParseCloseWithStatusReasonCase7_3_4()
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
        capture.assertHasFrame(CloseFrame.class,1);

        CloseFrame pActual = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(messageBytes.length + 2));
        ByteBufferAssert.assertSize("CloseFrame.payload",messageBytes.length + 2,pActual.getPayload());

    }

    @Test
    public void testParseEmptyCloseCase7_3_1()
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
        capture.assertHasFrame(CloseFrame.class,1);

        CloseFrame pActual = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(0));
        ByteBufferAssert.assertSize("CloseFrame.payload",0,pActual.getPayload());

    }
}
