package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.junit.Test;

public class PingParserTest
{
    @Test
    public void testBasicPingParsing()
    {
        Debug.enableDebugLogging(Parser.class);

        ByteBuffer buf = ByteBuffer.allocate(7);
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        Debug.dumpState(buf);
        buf.flip();
        Debug.dumpState(buf);

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PingFrame.class,1);
    }
}
