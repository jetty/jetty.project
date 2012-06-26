package org.eclipse.jetty.websocket.ab;


import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ABCase1
{
    StandardByteBufferPool bufferPool = new StandardByteBufferPool();
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
    

    @Test
    public void testGenerateEmptyTextCase1_1_1()
    {
        TextFrame textFrame = new TextFrame("");
        textFrame.setFin(true);
        
        Generator generator = new Generator(bufferPool,policy);
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });
        
        actual.flip();
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);

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
        capture.assertHasFrame(TextFrame.class,1);
        
        TextFrame pActual = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(0));
        ByteBufferAssert.assertSize("TextFrame.payload",0,pActual.getPayload());    
    }
    
    @Test
    public void testGenerate125ByteTextCase1_1_2()
    {
        int length = 125;
        
        StringBuilder builder = new StringBuilder();
        
        for ( int i = 0 ; i < length ; ++i)
        {
            builder.append("*");
        }
        
        TextFrame textFrame = new TextFrame(builder.toString());
        textFrame.setFin(true);
        
        Generator generator = new Generator(bufferPool,policy);
        ByteBuffer actual = generator.generate(textFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
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
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
        
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);
        
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        
        TextFrame pActual = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        ByteBufferAssert.assertSize("TextFrame.payload",length,pActual.getPayload());    
    }
    
    @Test
    public void testGenerate126ByteTextCase1_1_3()
    {
        int length = 126;
        
        StringBuilder builder = new StringBuilder();
        
        for ( int i = 0 ; i < length ; ++i)
        {
            builder.append("*");
        }
        
        TextFrame textFrame = new TextFrame(builder.toString());
        textFrame.setFin(true);
        
        Generator generator = new Generator(bufferPool,policy);
        ByteBuffer actual = generator.generate(textFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        
        //expected.put((byte)((length>>8) & 0xFF)); 
        //expected.put((byte)(length & 0xFF));    
        expected.putShort((short)length);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        actual.flip();
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
        
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

        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
        
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);
        
        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        
        TextFrame pActual = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        ByteBufferAssert.assertSize("TextFrame.payload",length,pActual.getPayload());    
    }
}
