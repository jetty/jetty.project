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
    public RetainableByteBuffer data()
    {
        return slice;
    }

    public void rewind()
    {
        slice.release();
        slice = data.slice();
    }

    @Override
    public boolean release()
    {
        return data.release();
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d,remaining/length=%d/%d]".formatted(super.toString(), offset(), data().size(), length());
    }
}
