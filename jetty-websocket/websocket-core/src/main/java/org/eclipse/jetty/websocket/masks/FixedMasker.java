//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.masks;

public class FixedMasker implements Masker
{
    private final byte[] _mask;

    public FixedMasker()
    {
        this(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff});
    }

    public FixedMasker(byte[] mask)
    {
        _mask=new byte[4];
        // Copy to avoid that external code keeps a reference
        // to the array parameter to modify masking on-the-fly
        System.arraycopy(mask, 0, _mask, 0, 4);
    }

    @Override
    public void genMask(byte[] mask)
    {
        System.arraycopy(_mask, 0, mask, 0, 4);
    }
}
