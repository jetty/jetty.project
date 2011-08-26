package org.eclipse.jetty.websocket;

import java.util.Random;


public class RandomMaskGen implements MaskGen
{
    final Random _random;
    public RandomMaskGen()
    {
        _random=new Random(); 
    }
    
    public RandomMaskGen(Random random)
    {
        _random=random;
    }
    
    public void genMask(byte[] mask)
    {
        _random.nextBytes(mask);
    }
}