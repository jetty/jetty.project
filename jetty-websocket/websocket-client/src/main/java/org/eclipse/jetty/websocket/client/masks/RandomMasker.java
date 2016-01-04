//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.masks;

import java.util.Random;

import org.eclipse.jetty.websocket.common.WebSocketFrame;

public class RandomMasker implements Masker
{
    private final Random random;

    public RandomMasker()
    {
        this(new Random());
    }

    public RandomMasker(Random random)
    {
        this.random = random;
    }

    @Override
    public void setMask(WebSocketFrame frame)
    {
        byte mask[] = new byte[4];
        random.nextBytes(mask);
        frame.setMask(mask);
    }
}
