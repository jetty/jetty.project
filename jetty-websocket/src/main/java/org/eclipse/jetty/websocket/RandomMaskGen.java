package org.eclipse.jetty.websocket;

import java.util.Random;


public class RandomMaskGen implements MaskGen
{
    private final Random _random;

    public RandomMaskGen()
    {
        this(new Random());
    }

    public RandomMaskGen(Random random)
    {
        _random=random;
    }

    public void genMask(byte[] mask)
    {
        // The assumption is that this code is always called
        // with an external lock held to prevent concurrent access
        // Otherwise we need to synchronize on the _random.
        _random.nextBytes(mask);
    }
}
