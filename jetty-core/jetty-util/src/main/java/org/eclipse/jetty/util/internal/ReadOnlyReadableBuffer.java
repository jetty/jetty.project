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

package org.eclipse.jetty.util.internal;

import java.io.IOException;
import java.nio.ReadOnlyBufferException;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class ReadOnlyReadableBuffer implements ReadableBuffer
{
    private final ReadableBuffer delegate;

    public ReadOnlyReadableBuffer(ReadableBuffer delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public long position()
    {
        return delegate.position();
    }

    @Override
    public void position(long newPosition)
    {
        delegate.position(newPosition);
    }

    @Override
    public long capacity()
    {
        return delegate.capacity();
    }

    @Override
    public long remaining()
    {
        return delegate.remaining();
    }

    @Override
    public byte get(long index)
    {
        return delegate.get(index);
    }

    @Override
    public byte get()
    {
        return delegate.get();
    }

    @Override
    public int getAsInt()
    {
        return delegate.getAsInt();
    }

    @Override
    public short getShort()
    {
        return delegate.getShort();
    }

    @Override
    public int getShort(long index)
    {
        return delegate.getShort(index);
    }

    @Override
    public int getShortAsInt()
    {
        return delegate.getShortAsInt();
    }

    @Override
    public int getInt()
    {
        return delegate.getInt();
    }

    @Override
    public int getInt(long index)
    {
        return delegate.getInt(index);
    }

    @Override
    public long getLong()
    {
        return delegate.getLong();
    }

    @Override
    public long getLong(long index)
    {
        return delegate.getLong(index);
    }

    @Override
    public void get(byte[] b)
    {
        delegate.get(b);
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        delegate.get(b, off, len);
    }

    @Override
    public ReadableBuffer slice()
    {
        return delegate.slice();
    }

    @Override
    public ReadableBuffer slice(long position, long length)
    {
        return delegate.slice(position, length);
    }

    @Override
    public WritableBuffer compact()
    {
        throw new ReadOnlyBufferException();
    }

    @Override
    public WritableBuffer toWritable()
    {
        throw new ReadOnlyBufferException();
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        return delegate.writeTo(target);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{d=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            delegate);
    }

    // Retainable

    @Override
    public boolean canRetain()
    {
        return delegate.canRetain();
    }

    @Override
    public boolean isRetained()
    {
        return delegate.isRetained();
    }

    @Override
    public void retain()
    {
        delegate.retain();
    }

    @Override
    public boolean release()
    {
        return delegate.release();
    }

    @Override
    public int getRetained()
    {
        return delegate.getRetained();
    }
}
