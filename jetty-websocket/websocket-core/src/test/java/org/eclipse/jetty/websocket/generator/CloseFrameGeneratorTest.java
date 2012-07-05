package org.eclipse.jetty.websocket.generator;

import junit.framework.Assert;

import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.junit.Test;

public class CloseFrameGeneratorTest
{
    @Test
    public void testGenerator() throws Exception
    {
        CloseFrame close = new CloseFrame();

        Assert.assertEquals(OpCode.CLOSE,close.getOpCode());
    }
}
