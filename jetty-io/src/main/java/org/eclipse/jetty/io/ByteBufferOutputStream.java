//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.io;

import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Simple wrapper of a ByteBuffer as an OutputStream.
 * The buffer does not grow and this class will throw an
 * {@link java.nio.BufferOverflowException} if the buffer capacity is exceeded.
 */
public class ByteBufferOutputStream extends OutputStream
{
    final ByteBuffer _buffer;

    public ByteBufferOutputStream(ByteBuffer buffer)
    {
        _buffer = buffer;
    }

    public void close()
    {
    }

    public void flush()
    {
    }

    public void write(byte[] b)
    {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len)
    {
        BufferUtil.append(_buffer, b, off, len);
    }

    public void write(int b)
    {
        BufferUtil.append(_buffer, (byte)b);
    }
}
