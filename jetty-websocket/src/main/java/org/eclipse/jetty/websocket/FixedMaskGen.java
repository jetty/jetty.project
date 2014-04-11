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
