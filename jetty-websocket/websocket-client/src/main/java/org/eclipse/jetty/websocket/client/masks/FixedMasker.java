//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Arrays;

import org.eclipse.jetty.websocket.common.WebSocketFrame;

public class FixedMasker implements Masker
{
    private final byte[] mask;

    public FixedMasker()
    {
        this(new byte[]
            {(byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff});
    }

    public FixedMasker(byte[] mask)
    {
        // Copy to avoid that external code keeps a reference
        // to the array parameter to modify masking on-the-fly
        this.mask = Arrays.copyOf(mask, 4);
    }

    @Override
    public void setMask(WebSocketFrame frame)
    {
        frame.setMask(mask);
    }
}
