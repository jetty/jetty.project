package org.eclipse.jetty.websocket.asserts;

import junit.framework.Assert;

import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.frames.CloseFrame;

public class CloseFrameAssert
{

    public static void assertValidCloseFrame( CloseFrame close )
    {
        Assert.assertEquals(OpCode.CLOSE, close.getOpCode() );
    }
    
}
