package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.TestLogging;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.junit.Test;

public class PingParserTest
{
    @Test
    public void testBasicPingParsing()
    {
        TestLogging.enableDebug(Parser.class);

        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        capture.assertHasFrame(PingFrame.class,1);
    }
}
