package org.eclipse.jetty.websocket.ab;

import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.junit.Test;

public class TestABCase3
{
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV1PingFrame()
    {
        PingFrame pingFrame = new PingFrame();
        
        pingFrame.setRsv1(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV2PingFrame()
    {
        PingFrame pingFrame = new PingFrame();
        
        pingFrame.setRsv2(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV3PingFrame()
    {
        PingFrame pingFrame = new PingFrame();
        
        pingFrame.setRsv3(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV1PongFrame()
    {
        PongFrame pongFrame = new PongFrame();
        
        pongFrame.setRsv1(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV2PongFrame()
    {
        PongFrame pongFrame = new PongFrame();
        
        pongFrame.setRsv2(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV3PongFrame()
    {
        PongFrame pongFrame = new PongFrame();
        
        pongFrame.setRsv3(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV1CloseFrame()
    {
        CloseFrame closeFrame = new CloseFrame();
        
        closeFrame.setRsv1(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV2CloseFrame()
    {
        CloseFrame closeFrame = new CloseFrame();
        
        closeFrame.setRsv2(true);
    }
    
    @Test( expected=IllegalArgumentException.class )
    public void testGenerateRSV3CloseFrame()
    {
        CloseFrame closeFrame = new CloseFrame();
        
        closeFrame.setRsv3(true);
    }
}
