package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;

public class ParserTest
{
    @Test
    public void testParseNothing()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Put nothing in the buffer.
        buf.flip();

        Parser parser = new Parser();
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        Assert.assertThat("Frame Count",capture.getFrames().size(),is(0));
    }
}
