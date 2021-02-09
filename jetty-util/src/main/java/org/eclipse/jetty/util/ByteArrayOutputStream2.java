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

package org.eclipse.jetty.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * ByteArrayOutputStream with public internals
 */
public class ByteArrayOutputStream2 extends ByteArrayOutputStream
{
    public ByteArrayOutputStream2()
    {
        super();
    }

    public ByteArrayOutputStream2(int size)
    {
        super(size);
    }

    public byte[] getBuf()
    {
        return buf;
    }

    public int getCount()
    {
        return count;
    }

    public void setCount(int count)
    {
        this.count = count;
    }

    public void reset(int minSize)
    {
        reset();
        if (buf.length < minSize)
        {
            buf = new byte[minSize];
        }
    }

    public void writeUnchecked(int b)
    {
        buf[count++] = (byte)b;
    }

    public String toString(Charset charset)
    {
        return new String(buf, 0, count, charset);
    }
}
