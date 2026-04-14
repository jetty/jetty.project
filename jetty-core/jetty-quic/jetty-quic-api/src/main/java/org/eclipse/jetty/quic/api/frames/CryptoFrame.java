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

import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.io.RetainableByteBuffer;

public final class CryptoFrame extends Frame.Abstract implements Frame.WithData
{
    private final long offset;
    private final long length;
    private final RetainableByteBuffer data;
    private RetainableByteBuffer slice;

    public CryptoFrame(long offset, RetainableByteBuffer data)
    {
        super(0x06);
        this.offset = offset;
        this.length = data.size();
        this.data = data.slice();
        this.slice = data;
        this.slice.retain();
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
        return slice.remaining();
    }

    public long skip(long skip)
    {
        return slice.skip(skip);
    }

    @Override
    public CryptoFrame slice(long offset, long length)
    {
        return new CryptoFrame(offset, slice.slice(length));
    }

    @Override
    public void accept(Consumer<RetainableByteBuffer> consumer)
    {
        consumer.accept(slice);
    }

    @Override
    public <T> T map(Function<RetainableByteBuffer, T> mapper)
    {
        return mapper.apply(slice);
    }

    public void rewind()
    {
        slice.release();
        slice = data.slice();
    }

    @Override
    public void close()
    {
        slice.release();
        data.release();
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d,remaining/length=%d/%d]".formatted(super.toString(), offset(), remaining(), length());
    }
}
