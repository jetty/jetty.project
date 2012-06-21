package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.PolicyViolationException;
import org.eclipse.jetty.websocket.api.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Assert;
import org.junit.Test;

public class TextPayloadParserTest
{
    @Test
    public void testFrameTooLargeDueToPolicy() throws Exception
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMaxTextMessageSize(1024); // set policy
        byte utf[] = new byte[2048];
        Arrays.fill(utf,(byte)'a');

        Assert.assertThat("Must be a medium length payload",utf.length,allOf(greaterThan(0x7E),lessThan(0xFFFF)));

        ByteBuffer buf = ByteBuffer.allocate(utf.length + 8);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertHasErrors(PolicyViolationException.class,1);
        capture.assertHasNoFrames();

        PolicyViolationException err = (PolicyViolationException)capture.getErrors().get(0);
        Assert.assertThat("Error.closeCode",err.getCloseCode(),is(WebSocket.CLOSE_POLICY_VIOLATION));
    }

    @Test
    public void testLongMaskedText() throws Exception
    {
        StringBuffer sb = new StringBuffer(); ;
        for (int i = 0; i < 3500; i++)
        {
            sb.append("Hell\uFF4f Big W\uFF4Frld ");
        }
        sb.append(". The end.");

        String expectedText = sb.toString();
        byte utf[] = expectedText.getBytes(StringUtil.__UTF8);

        Assert.assertThat("Must be a long length payload",utf.length,greaterThan(0xFFFF));

        ByteBuffer buf = ByteBuffer.allocate(utf.length + 10);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | 0x7F)); // 0x7F == 127 (a 4 byte payload length)
        buf.putInt(utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsText(),is(expectedText));
    }

    @Test
    public void testMediumMaskedText() throws Exception
    {
        StringBuffer sb = new StringBuffer(); ;
        for (int i = 0; i < 14; i++)
        {
            sb.append("Hell\uFF4f Medium W\uFF4Frld ");
        }
        sb.append(". The end.");

        String expectedText = sb.toString();
        byte utf[] = expectedText.getBytes(StringUtil.__UTF8);

        Assert.assertThat("Must be a medium length payload",utf.length,allOf(greaterThan(0x7E),lessThan(0xFFFF)));

        ByteBuffer buf = ByteBuffer.allocate(utf.length + 10);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsText(),is(expectedText));
    }

    @Test
    public void testShortMaskedFragmentedText() throws Exception
    {
        String part1 = "Hello ";
        String part2 = "World";

        byte b1[] = part1.getBytes(StringUtil.__UTF8_CHARSET);
        byte b2[] = part2.getBytes(StringUtil.__UTF8_CHARSET);

        ByteBuffer buf = ByteBuffer.allocate(32);

        // part 1
        buf.put((byte)0x01); // no fin + text
        buf.put((byte)(0x80 | b1.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,b1);

        // part 2
        buf.put((byte)0x80); // fin + continuation
        buf.put((byte)(0x80 | b2.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,b2);

        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,2);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame[0].data",txt.getPayloadAsText(),is(part1));
        txt = (TextFrame)capture.getFrames().get(1);
        Assert.assertThat("TextFrame[1].data",txt.getPayloadAsText(),is(part2));
    }

    @Test
    public void testShortMaskedText() throws Exception
    {
        String expectedText = "Hello World";
        byte utf[] = expectedText.getBytes(StringUtil.__UTF8_CHARSET);

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsText(),is(expectedText));
    }

    @Test
    public void testShortMaskedUtf8Text() throws Exception
    {
        String expectedText = "Hell\uFF4f W\uFF4Frld";

        byte utf[] = expectedText.getBytes(StringUtil.__UTF8);

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(TextFrame.class,1);
        TextFrame txt = (TextFrame)capture.getFrames().get(0);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsText(),is(expectedText));
    }
}
