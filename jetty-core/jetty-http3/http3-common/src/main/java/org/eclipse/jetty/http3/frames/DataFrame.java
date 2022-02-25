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

package org.eclipse.jetty.http3.frames;

import java.nio.ByteBuffer;

public class DataFrame extends Frame
{
    private final ByteBuffer data;
    private final boolean last;
    private final int length;

    public DataFrame(ByteBuffer data, boolean last)
    {
        super(FrameType.DATA);
        this.data = data;
        this.last = last;
        this.length = data.remaining();
    }

    public ByteBuffer getByteBuffer()
    {
        return data;
    }

    public boolean isLast()
    {
        return last;
    }

    @Override
    public String toString()
    {
        return String.format("%s[last=%b,length=%d]", super.toString(), isLast(), length);
    }
}
