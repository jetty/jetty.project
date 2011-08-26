package org.eclipse.jetty.websocket;


public class ZeroMaskGen implements MaskGen
{
    public void genMask(byte[] mask)
    {
        mask[0]=mask[1]=mask[2]=mask[3]=0;
    }
}