package org.eclipse.jetty.websocket.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.parser.FrameParseCapture;
import org.eclipse.jetty.websocket.parser.Parser;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(value = Parameterized.class)
public class TestABCase7_9
{
    @Parameters
    public static Collection<Integer[]> data()
    {
        List<Integer[]> data = new ArrayList<>();
        // @formatter:off
        data.add(new Integer[]
                { 1004 });
        data.add(new Integer[]
                { 1005 });
        data.add(new Integer[]
                { 1006 });
        data.add(new Integer[]
                { 1012 });
        data.add(new Integer[]
                { 1013 });
        data.add(new Integer[]
                { 1014 });
        data.add(new Integer[]
                { 1015 });
        data.add(new Integer[]
                { 1016 });
        data.add(new Integer[]
                { 1100 });
        data.add(new Integer[]
                { 2000 });
        data.add(new Integer[]
                { 2999 });

        // @formatter:on
        return data;
    }

    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    private int invalidStatusCode;

    public TestABCase7_9(Integer invalidStatusCode)
    {
        this.invalidStatusCode = invalidStatusCode.intValue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCase7_9GenerateInvalidCloseStatus()
    {
        CloseFrame closeFrame = new CloseFrame(invalidStatusCode);
    }

    @Test
    public void testCase7_9ParseInvalidCloseStatus()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88, 0x02 });

        expected.putChar((char)invalidStatusCode);
        expected.flip();

        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);
        parser.parse(expected);

        Assert.assertEquals("error on invalid close status code",1,capture.getErrorCount(WebSocketException.class));

        WebSocketException known = capture.getErrors().get(0);

        Assert.assertTrue("reserved should be in message",known.getMessage().contains("reserved"));
    }


}
