package org.eclipse.jetty.quic.api.frames;

public class PaddingFrame extends Frame.Abstract
{
    public PaddingFrame()
    {
        super(0x00);
    }
}
