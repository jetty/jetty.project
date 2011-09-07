package org.eclipse.jetty.websocket;


public class FixedMaskGen implements MaskGen
{
    private final byte[] _mask;

    public FixedMaskGen()
    {
        this(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff});
    }

    public FixedMaskGen(byte[] mask)
    {
        _mask=new byte[4];
        // Copy to avoid that external code keeps a reference
        // to the array parameter to modify masking on-the-fly
        System.arraycopy(mask, 0, _mask, 0, 4);
    }

    public void genMask(byte[] mask)
    {
        System.arraycopy(_mask, 0, mask, 0, 4);
    }
}
