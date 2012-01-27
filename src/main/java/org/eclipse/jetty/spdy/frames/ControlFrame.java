package org.eclipse.jetty.spdy.frames;

public abstract class ControlFrame
{
    public static final int HEADER_LENGTH = 8;

    private final short version;
    private final ControlFrameType type;
    private final byte flags;

    public ControlFrame(short version, ControlFrameType type, byte flags)
    {
        this.version = version;
        this.type = type;
        this.flags = flags;
    }

    public short getVersion()
    {
        return version;
    }

    public ControlFrameType getType()
    {
        return type;
    }

    public byte getFlags()
    {
        return flags;
    }

    @Override
    public String toString()
    {
        return getType() + " v" + getVersion();
    }
}
