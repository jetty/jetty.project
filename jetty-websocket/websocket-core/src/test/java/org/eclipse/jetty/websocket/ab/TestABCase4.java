package org.eclipse.jetty.websocket.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase4
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    @Test
    public void testParserControlOpCode11Case4_2_1()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x8b, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.setListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 11"));
    }

    @Test
    public void testParserControlOpCode12WithPayloadCase4_2_2()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x8c, 0x01, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.setListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 12"));
    }


    @Test
    public void testParserNonControlOpCode3Case4_1_1()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x83, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.setListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 3"));
    }

    @Test
    public void testParserNonControlOpCode4WithPayloadCase4_1_2()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x84, 0x01, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.setListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error on undefined opcode", 1, capture.getErrorCount(WebSocketException.class)) ;

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("Unknown opcode: 4"));
    }
}
