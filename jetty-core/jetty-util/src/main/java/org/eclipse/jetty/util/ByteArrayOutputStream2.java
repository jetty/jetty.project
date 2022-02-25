//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
