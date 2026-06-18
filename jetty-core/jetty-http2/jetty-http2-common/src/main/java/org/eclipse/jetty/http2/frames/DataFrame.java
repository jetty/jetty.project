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

package org.eclipse.jetty.http2.frames;

import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.util.buffer.ReadableBuffer;

public class DataFrame extends StreamFrame implements Retainable
{
    private final ReadableBuffer data;
    private final boolean endStream;
    private final long length;
    private final int padding;

    public DataFrame(ReadableBuffer data, boolean endStream)
    {
        this(0, data, endStream);
    }

    public DataFrame(int streamId, ReadableBuffer data, boolean endStream)
    {
        this(streamId, data, endStream, 0);
    }

    public DataFrame(int streamId, ReadableBuffer data, boolean endStream, int padding)
    {
        super(FrameType.DATA, streamId);
        this.data = data;
        data.retain();
        this.endStream = endStream;
        this.length = data.remaining();
        this.padding = padding;
    }

    public static DataFrame eof(int streamId)
    {
        return new DataFrame(streamId, ReadableBuffer.EMPTY, true);
    }

    public ReadableBuffer acquire()
    {
        data.retain();
        return data;
    }

    public boolean isEndStream()
    {
        return endStream;
    }

    /**
     * @return the number of data bytes remaining.
     */
    public long remaining()
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

    /**
     * @return the flow control length, equivalent to the sum of data bytes and padding bytes
     */
    public int flowControlLength()
    {
        // TODO: overflow?
        return Math.toIntExact(length + padding);
    }

    @Override
    public DataFrame withStreamId(int streamId)
    {
        return new DataFrame(streamId, data, isEndStream());
    }

    @Override
    public boolean canRetain()
    {
        return data.canRetain();
    }

    @Override
    public boolean isRetained()
    {
        return data.isRetained();
    }

    @Override
    public void retain()
    {
        data.retain();
    }

    @Override
    public boolean release()
    {
        return data.release();
    }

    @Override
    public int getRetained()
    {
        return data.getRetained();
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d{length:%d,end=%b}", super.toString(), getStreamId(), length, isEndStream());
    }
}
