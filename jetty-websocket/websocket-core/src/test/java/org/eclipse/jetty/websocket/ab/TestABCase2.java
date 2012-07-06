package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
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

public class TestABCase2
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    @Test
    public void testGenerate125OctetPingCase2_4()
    {
        byte[] bytes = new byte[125];

        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }

        WebSocketFrame pingFrame = FrameBuilder.ping().payload(bytes).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(bytes.length + 32);
        generator.generate(actual, pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);


        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerateBinaryPingCase2_3()
    {
        byte[] bytes = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

        WebSocketFrame pingFrame = FrameBuilder.ping().payload(bytes).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);


        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }


    @Test
    public void testGenerateEmptyPingCase2_1()
    {
        WebSocketFrame pingFrame = FrameBuilder.ping().asFrame();


        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x89, (byte)0x00 });

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerateHelloPingCase2_2()
    {
        String message = "Hello, world!";
        byte[] messageBytes = message.getBytes();

        WebSocketFrame pingFrame = FrameBuilder.ping(message).asFrame();

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);


        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test( expected=WebSocketException.class )
    public void testGenerateOversizedBinaryPingCase2_5_A()
    {
        byte[] bytes = new byte[126];

        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = 0x00;
        }

        FrameBuilder.ping().payload(bytes);
    }

    @Test( expected=WebSocketException.class )
    public void testGenerateOversizedBinaryPingCase2_5_B()
    {
        byte[] bytes = new byte[126];

        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = 0x00;
        }

        WebSocketFrame pingFrame = FrameBuilder.ping().payload(bytes).asFrame();

        Generator generator = new Generator(WebSocketPolicy.newServerPolicy());
        generator.generate(ByteBuffer.allocate(bytes.length + 32),pingFrame);
    }

    @Test
    public void testParse125OctetPingCase2_4()
    {
        byte[] bytes = new byte[125];

        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.PING,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(bytes.length));
        Assert.assertEquals("PingFrame.payload",bytes.length,pActual.getPayloadLength());
    }

    @Test
    public void testParseBinaryPingCase2_3()
    {
        byte[] bytes = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.PING,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(bytes.length));
        Assert.assertEquals("PingFrame.payload",bytes.length,pActual.getPayloadLength());
    }

    @Test
    public void testParseEmptyPingCase2_1()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x89, (byte)0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.PING,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(0));
        Assert.assertEquals("PingFrame.payload",0,pActual.getPayloadLength());
    }

    @Test
    public void testParseHelloPingCase2_2()
    {
        String message = "Hello, world!";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.PING,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(message.length()));
        Assert.assertEquals("PingFrame.payload",message.length(),pActual.getPayloadLength());

    }


    @Test
    public void testParseOversizedBinaryPingCase2_5()
    {
        byte[] bytes = new byte[126];

        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = 0x00;
        }

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);

        expected.put(new byte[]
                { (byte)0x89 });

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        Assert.assertEquals( "error should be returned for too large of ping payload", 1, capture.getErrorCount(WebSocketException.class)) ;
    }

}
