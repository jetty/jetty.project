package org.eclipse.jetty.websocket.ab;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.websocket.api.PolicyViolationException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(value = Parameterized.class)
public class TestABCase3
{

    @Parameters
    public static Collection<WebSocketFrame[]> data()
    {
        List<WebSocketFrame[]> data = new ArrayList<>();
        // @formatter:off
        data.add(new WebSocketFrame[]
                { FrameBuilder.ping().rsv1(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.ping().rsv2(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.ping().rsv3(true).asFrame() });
        data.add(new WebSocketFrame[]
        { FrameBuilder.pong().rsv1(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.pong().rsv2(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.pong().rsv3(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.close().rsv1(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.close().rsv2(true).asFrame() });
        data.add(new WebSocketFrame[]
                { FrameBuilder.close().rsv3(true).asFrame() });
        // @formatter:on
        return data;
    }

    private WebSocketFrame invalidFrame;

    public TestABCase3(WebSocketFrame invalidFrame)
    {
        this.invalidFrame = invalidFrame;
    }

    @Test(expected = PolicyViolationException.class)
    public void testGenerateRSV1CloseFrame()
    {
        Generator generator = new Generator(WebSocketPolicy.newServerPolicy());

        generator.generate(ByteBuffer.allocate(32),invalidFrame);
    }


}
