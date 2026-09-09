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

package org.eclipse.jetty.http3.frames;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class DataFrame extends Frame implements AutoCloseable
{
    private final RetainableByteBuffer data;
    private final boolean last;
    private final long length;

    public DataFrame(RetainableByteBuffer data, boolean last)
    {
        super(FrameType.DATA);
        data.retain();
        this.data = data;
        this.last = last;
        this.length = data.remaining();
    }

    public RetainableByteBuffer acquire()
    {
        data.retain();
        return data;
    }

    public long remaining()
    {
        return data.remaining();
    }

    public boolean isLast()
    {
        return last;
    }

    @Override
    public void close()
    {
        data.release();
    }

    @Override
    public String toString()
    {
        return String.format("%s[last=%b,length=%d]", super.toString(), isLast(), length);
    }
}
