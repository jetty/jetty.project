package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.frames.ControlFrameType;

public class ControlFrame extends BaseFrame 
{
    private final short _version;
    private final ControlFrameType _type; 
    private final byte _flags; // check if needed

    
    public ControlFrame( short version, ControlFrameType type, byte flags )
    {
        _version = version;
        _type = type;
        _flags = flags;
    }
    
    public short getVersion()
    {
        return _version;
    }

    public ControlFrameType getType()
    {
        return _type;
    }
    
    @Override
    public String toString()
    {
        return String.format("%s frame v%s", getType(), getVersion());
    }
}
