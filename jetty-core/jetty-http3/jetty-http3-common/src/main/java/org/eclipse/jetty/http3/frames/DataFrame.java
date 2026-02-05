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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;

public class DataFrame extends Frame
{
    private final ByteBuffer data;
    private final Content.Source.Seekable source;
    private final boolean last;
    private final long length;

    public DataFrame(ByteBuffer data, boolean last)
    {
        this(data, null, last);
    }

    public DataFrame(Content.Source.Seekable source, boolean last)
    {
        this(Content.Sink.CONTENT_SOURCE, source, last);
    }

    private DataFrame(ByteBuffer data, Content.Source.Seekable source, boolean last)
    {
        super(FrameType.DATA);
        this.data = data;
        this.source = source;
        this.last = last;
        this.length = remaining();
    }

    public ByteBuffer getByteBuffer()
    {
        return data;
    }

    public Content.Source.Seekable getContentSource()
    {
        return source;
    }

    public boolean isLast()
    {
        return last;
    }

    public long remaining()
    {
        return data == Content.Sink.CONTENT_SOURCE ? source.remaining() : data.remaining();
    }

    @Override
    public String toString()
    {
        return String.format("%s[last=%b,length=%d]", super.toString(), isLast(), length);
    }
}
