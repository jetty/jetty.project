package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.parser.PingPayloadParser;
import org.junit.Assert;
import org.junit.Test;

public class ABCase7_3
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

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
    
    
    @Test (expected = WebSocketException.class)
    public void testGenerate1BytePayloadCloseCase7_3_2()
    {
        CloseFrame pingFrame = new CloseFrame();
        pingFrame.setPayload(new byte[] {0x00});
        
        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, pingFrame);
    }
    
    @Test
    public void testParse1BytePayloadCloseCase7_3_2()
    {      
        //Debug.enableDebugLogging(Parser.class);
        
        String message = "*";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88 });
        
        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);
        
        expected.flip();
        
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);
        
        Assert.assertEquals( "error on invalid close payload", 1, capture.getErrorCount(WebSocketException.class)) ;
      
        WebSocketException known = capture.getErrors().get(0);
        
        Assert.assertTrue("undefined option should be in message",known.getMessage().contains("invalid payload length"));
    }
}
