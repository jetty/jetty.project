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

public class CryptoFrame extends Frame implements Frame.WithOffset, Comparable<CryptoFrame>
{
    private final long offset;
    private final long length;
    private final RetainableByteBuffer data;

    public CryptoFrame(long offset, RetainableByteBuffer data)
    {
        super(0x06);
        this.offset = offset;
        this.length = data.remaining();
        this.data = data;
    }

    @Override
    public long getOffset()
    {
        return offset;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    public RetainableByteBuffer getData()
    {
        return data;
    }

    @Override
    public int compareTo(CryptoFrame that)
    {
        return Long.compare(offset, that.offset);
    }
}
