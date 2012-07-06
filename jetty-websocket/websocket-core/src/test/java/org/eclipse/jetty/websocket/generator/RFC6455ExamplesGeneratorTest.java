package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Test;

public class RFC6455ExamplesGeneratorTest
{
    private static final int FUDGE = 32;

    @Test
    public void testFragmentedUnmaskedTextMessage()
    {
        WebSocketFrame text1 = FrameBuilder.text("Hel").fin(false).asFrame();
        WebSocketFrame text2 = FrameBuilder.continuation("lo").asFrame();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

        Generator generator = new Generator(policy);

        ByteBuffer actual1 = ByteBuffer.allocate(32);
        ByteBuffer actual2 = ByteBuffer.allocate(32);

        generator.generate(actual1,text1);
        generator.generate(actual2,text2);

        ByteBuffer expected1 = ByteBuffer.allocate(5);

        expected1.put(new byte[]
                { (byte)0x01, (byte)0x03, (byte)0x48, (byte)0x65, (byte)0x6c });

        ByteBuffer expected2 = ByteBuffer.allocate(4);

        expected2.put(new byte[]
                { (byte)0x80, (byte)0x02, (byte)0x6c, (byte)0x6f });

        expected1.flip();
        actual1.flip();
        expected2.flip();
        actual2.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal",expected1,actual1);
        ByteBufferAssert.assertEquals("t2 buffers are not equal",expected2,actual2);
    }

    @Test
    public void testSingleMaskedPongRequest()
    {
        WebSocketFrame pong = FrameBuilder.pong("Hello").mask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d }).asFrame();

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Generator gen = new Generator(policy);

        ByteBuffer actual = ByteBuffer.allocate(32);
        gen.generate(actual,pong);
        actual.flip(); // make readable

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        expected.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("pong buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleMaskedTextMessage()
    {
        WebSocketFrame text = FrameBuilder.text("Hello").mask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d }).asFrame();

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        Generator gen = new Generator(policy);

        ByteBuffer actual = ByteBuffer.allocate(32);
        gen.generate(actual,text);
        actual.flip(); // make readable

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        expected.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("masked text buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;

        WebSocketFrame binary = FrameBuilder.binary().asFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(payload);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Generator gen = new Generator(policy);

        ByteBuffer actual = ByteBuffer.allocate(dataSize + FUDGE);
        gen.generate(actual,binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + FUDGE);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        expected.put(new byte[]
                { (byte)0x82, (byte)0x7E });
        expected.putShort((short)0x01_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmasked64KBinaryMessage()
    {
        int dataSize = 1024 * 64;

        WebSocketFrame binary = FrameBuilder.binary().asFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(payload);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        Generator gen = new Generator(policy);

        ByteBuffer actual = ByteBuffer.allocate(dataSize + 10);
        gen.generate(actual,binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64k bytes binary message in a single unmasked frame
        expected.put(new byte[]
                { (byte)0x82, (byte)0x7F });
        expected.putInt(0x00_00_00_00);
        expected.putInt(0x00_01_00_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        actual.flip();
        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmaskedPingRequest() throws Exception
    {
        WebSocketFrame ping = FrameBuilder.ping("Hello").asFrame();

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        Generator gen = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        gen.generate(actual,ping);
        actual.flip(); // make readable

        ByteBuffer expected = ByteBuffer.allocate(10);
        expected.put(new byte[]
                { (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("Ping buffers",expected,actual);
    }

    @Test
    public void testSingleUnmaskedTextMessage()
    {
        WebSocketFrame text = FrameBuilder.text("Hello").asFrame();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

        Generator generator = new Generator(policy);

        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual,text);

        ByteBuffer expected = ByteBuffer.allocate(10);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });

        expected.flip();
        actual.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal",expected,actual);
    }
}
