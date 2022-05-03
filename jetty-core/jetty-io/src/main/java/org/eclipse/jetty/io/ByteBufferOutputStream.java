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
