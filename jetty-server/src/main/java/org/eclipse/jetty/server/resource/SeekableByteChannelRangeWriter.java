//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;

public class SeekableByteChannelRangeWriter implements RangeWriter
{
    private final SeekableByteChannel channel;
    private final int bufSize;
    private final ByteBuffer buffer;

    public SeekableByteChannelRangeWriter(SeekableByteChannel seekableByteChannel)
    {
        this.channel = seekableByteChannel;
        this.bufSize = IO.bufferSize;
        this.buffer = BufferUtil.allocate(this.bufSize);
    }

    @Override
    public void close() throws IOException
    {
        this.channel.close();
    }

    @Override
    public void writeTo(OutputStream outputStream, long skipTo, long length) throws IOException
    {
        this.channel.position(skipTo);

        // copy from channel to output stream
        long readTotal = 0;
        while (readTotal < length)
        {
            BufferUtil.clearToFill(buffer);
            int size = (int)Math.min(bufSize, length - readTotal);
            buffer.limit(size);
            int readLen = channel.read(buffer);
            BufferUtil.flipToFlush(buffer, 0);
            BufferUtil.writeTo(buffer, outputStream);
            readTotal += readLen;
        }
    }
}
