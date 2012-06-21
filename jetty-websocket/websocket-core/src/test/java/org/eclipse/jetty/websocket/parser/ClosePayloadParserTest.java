package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.api.WebSocket;
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
        Debug.enableDebugLogging(Parser.class);
        Debug.enableDebugLogging(ClosePayloadParser.class);
        String expectedReason = "Game Over";

        byte utf[] = expectedReason.getBytes(StringUtil.__UTF8_CHARSET);
        ByteBuffer payload = ByteBuffer.allocate(utf.length + 2);
        payload.putShort(WebSocket.CLOSE_NORMAL);
        payload.put(utf,0,utf.length);
        payload.flip();

        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)(0x80 | 0x08)); // fin + close
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
        CloseFrame txt = (CloseFrame)capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.statusCode",txt.getStatusCode(),is(WebSocket.CLOSE_NORMAL));
        Assert.assertThat("CloseFrame.data",txt.getReason(),is(expectedReason));
    }
}
