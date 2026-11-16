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

package org.eclipse.jetty.quic.api.frames;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public final class CryptoFrame extends Frame.Abstract implements Frame.WithData
{
    private final long offset;
    private final long length;
    private final RetainableByteBuffer original;
    private RetainableByteBuffer current;

    public CryptoFrame(long offset, RetainableByteBuffer data)
    {
        super(0x06);
        this.offset = offset;
        this.length = data.remaining();
        this.original = data;
        // Implicitly retains once.
        this.current = data.slice();
    }

    @Override
    public long offset()
    {
        return offset;
    }

    @Override
    public long length()
    {
        return length;
    }

    @Override
    public long remaining()
    {
        return current.remaining();
    }

    @Override
    public RetainableByteBuffer acquire()
    {
        current.retain();
        return current;
    }

    public void rewind()
    {
        RetainableByteBuffer slice = original.slice();
        current.release();
        current = slice;
    }

    @Override
    public void close()
    {
        current.release();
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d,remaining/length=%d/%d]".formatted(super.toString(), offset(), remaining(), length());
    }
}
