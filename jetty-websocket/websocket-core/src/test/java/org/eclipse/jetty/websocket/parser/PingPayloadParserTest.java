package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.junit.Test;

public class PingPayloadParserTest
{
    @Test
    public void testBasicPingParsing()
    {
        Debug.enableDebugLogging(Parser.class);

        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PingFrame.class,1);
        PingFrame ping = (PingFrame)capture.getFrames().get(0);
        ByteBufferAssert.assertEquals("PingFrame.payload","Hello",ping.getPayload());
    }
}
