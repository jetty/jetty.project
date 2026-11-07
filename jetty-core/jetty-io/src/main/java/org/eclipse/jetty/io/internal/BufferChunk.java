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

package org.eclipse.jetty.io.internal;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class BufferChunk implements Content.Chunk
{
    private final RetainableByteBuffer buffer;
    private final boolean last;

    public BufferChunk(RetainableByteBuffer buffer, boolean last)
    {
        this.buffer = buffer;
        this.last = last;
        buffer.retain();
    }

    @Override
    public RetainableByteBuffer acquire()
    {
        buffer.retain();
        return buffer;
    }

    @Override
    public boolean isLast()
    {
        return last;
    }

    @Override
    public long remaining()
    {
        return buffer.remaining();
    }

    @Override
    public boolean hasRemaining()
    {
        return buffer.hasRemaining();
    }

    @Override
    public boolean isRetained()
    {
        return buffer.isRetained();
    }

    @Override
    public void retain()
    {
        buffer.retain();
    }

    @Override
    public boolean release()
    {
        return buffer.release();
    }

    @Override
    public void close()
    {
        buffer.close();
    }

    @Override
    public String toString()
    {
        return "%s@%x[last=%b,buffer=%s]".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            isLast(),
            buffer
        );
    }
}
