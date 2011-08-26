package org.eclipse.jetty.websocket;


public class FixedMaskGen implements MaskGen
{
    final byte[] _mask;
    public FixedMaskGen()
    {
        _mask=new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
    }
    
    public FixedMaskGen(byte[] mask)
    {
        _mask=mask;
    }
    
    public void genMask(byte[] mask)
    {
        mask[0]=_mask[0];
        mask[1]=_mask[1];
        mask[2]=_mask[2];
        mask[3]=_mask[3];
    }
}