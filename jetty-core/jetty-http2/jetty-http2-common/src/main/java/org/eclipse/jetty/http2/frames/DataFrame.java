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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;

public class DataFrame extends StreamFrame
{
    private final ByteBuffer data;
    private final Content.Source.Seekable source;
    private final boolean endStream;
    private final int length;
    private final int padding;

    public DataFrame(ByteBuffer data, boolean endStream)
    {
        this(0, data, endStream);
    }

    public DataFrame(int streamId, ByteBuffer data, boolean endStream)
    {
        this(streamId, data, null, endStream, 0);
    }

    public DataFrame(int streamId, Content.Source.Seekable source, boolean endStream)
    {
        this(streamId, Content.Sink.CONTENT_SOURCE, source, endStream, 0);
    }

    public DataFrame(int streamId, ByteBuffer data, boolean endStream, int padding)
    {
        this(streamId, data, null, endStream, padding);
    }

    private DataFrame(int streamId, ByteBuffer data, Content.Source.Seekable source, boolean endStream, int padding)
    {
        super(FrameType.DATA, streamId);
        this.data = data;
        this.source = source;
        this.endStream = endStream;
        this.length = remaining();
        this.padding = padding;
    }

    public ByteBuffer getByteBuffer()
    {
        return data;
    }

    public Content.Source.Seekable getContentSource()
    {
        return source;
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
        return Math.toIntExact(bytesLeft());
    }

    /**
     * @return the number of data bytes remaining, as a {@code long}
     */
    public  long bytesLeft()
    {
        return data == Content.Sink.CONTENT_SOURCE ? source.remaining() : data.remaining();
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
        return length + padding;
    }

    @Override
    public DataFrame withStreamId(int streamId)
    {
        return new DataFrame(streamId, getByteBuffer(), isEndStream());
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d{length:%d,end=%b}", super.toString(), getStreamId(), length, isEndStream());
    }
}
