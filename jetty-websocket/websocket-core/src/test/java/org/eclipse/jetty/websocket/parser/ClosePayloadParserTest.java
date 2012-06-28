package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.junit.Assert;
import org.junit.Test;

public class ClosePayloadParserTest
{
    @Test
    public void testGameOver()
    {
        String expectedReason = "Game Over";

        byte utf[] = expectedReason.getBytes(StringUtil.__UTF8_CHARSET);
        ByteBuffer payload = ByteBuffer.allocate(utf.length + 2);
        payload.putShort(StatusCode.NORMAL);
        payload.put(utf,0,utf.length);
        payload.flip();

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)(0x80 | OpCode.CLOSE.getCode())); // fin + close
        buf.put((byte)(0x80 | payload.remaining()));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,payload);
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(CloseFrame.class,1);
        CloseFrame close = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.statusCode",(short)close.getStatusCode(),is(StatusCode.NORMAL));
        Assert.assertThat("CloseFrame.data",close.getReason(),is(expectedReason));
    }
}
