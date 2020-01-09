//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;

public class DataFrame extends Frame
{
    private final int streamId;
    private final ByteBuffer data;
    private final boolean endStream;
    private final int padding;

    public DataFrame(int streamId, ByteBuffer data, boolean endStream)
    {
        this(streamId, data, endStream, 0);
    }

    public DataFrame(int streamId, ByteBuffer data, boolean endStream, int padding)
    {
        super(FrameType.DATA);
        this.streamId = streamId;
        this.data = data;
        this.endStream = endStream;
        this.padding = padding;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public ByteBuffer getData()
    {
        return data;
    }

    public boolean isEndStream()
    {
        return endStream;
    }

    /**
     * @return the number of data bytes remaining.
     */
    public int remaining()
    {
        return data.remaining();
    }

    /**
     * @return the number of bytes used for padding that count towards flow control.
     */
    public int padding()
    {
        return padding;
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d{length:%d,end=%b}", super.toString(), streamId, data.remaining(), endStream);
    }
}
