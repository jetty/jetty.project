//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

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
