package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.ControlFrame;

public abstract class ControlFrameGenerator 
{
    
    public abstract ByteBuffer generate(ControlFrame frame);
}
