//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// Simple wrapper of a [RetainableByteBuffer] as an [OutputStream].
///
/// The buffer does not grow and this class will throw an
/// [java.nio.BufferOverflowException] if the buffer capacity is exceeded.
public class RetainableByteBufferOutputStream extends OutputStream
{
    private final RetainableByteBuffer.Mutable _buffer;

    public RetainableByteBufferOutputStream(RetainableByteBuffer.Mutable buffer)
    {
        buffer.retain();
        _buffer = buffer;
    }

    public void write(int b)
    {
        _buffer.put((byte)b);
    }

    public void write(byte[] b, int off, int len)
    {
        _buffer.put(b, off, len);
    }

    public void flush()
    {
    }

    public void close()
    {
        _buffer.close();
    }
}
