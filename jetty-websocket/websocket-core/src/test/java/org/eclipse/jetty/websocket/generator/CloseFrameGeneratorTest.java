package org.eclipse.jetty.websocket.generator;

import org.eclipse.jetty.websocket.asserts.CloseFrameAssert;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.junit.Test;

public class CloseFrameGeneratorTest
{
    @Test
    public void testGenerator() throws Exception
    {
        CloseFrame close = new CloseFrame();
        
        CloseFrameAssert.assertValidCloseFrame(close);
    }
    
}
