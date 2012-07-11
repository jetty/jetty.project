package org.eclipse.jetty.websocket.parser;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
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

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.setListener(capture);
        parser.parse(buf);

        capture.assertNoErrors();
        Assert.assertThat("Frame Count",capture.getFrames().size(),is(0));
    }
}
