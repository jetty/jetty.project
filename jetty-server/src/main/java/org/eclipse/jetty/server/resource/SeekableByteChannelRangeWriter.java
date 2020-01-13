//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
    public static final int NO_PROGRESS_LIMIT = 3;

    public interface ChannelSupplier
    {
        SeekableByteChannel newSeekableByteChannel() throws IOException;
    }

    private final ChannelSupplier channelSupplier;
    private final int bufSize;
    private final ByteBuffer buffer;
    private SeekableByteChannel channel;
    private long pos;
    private boolean defaultSeekMode = true;

    public SeekableByteChannelRangeWriter(SeekableByteChannelRangeWriter.ChannelSupplier channelSupplier)
    {
        this(null, channelSupplier);
    }

    public SeekableByteChannelRangeWriter(SeekableByteChannel initialChannel, SeekableByteChannelRangeWriter.ChannelSupplier channelSupplier)
    {
        this.channel = initialChannel;
        this.channelSupplier = channelSupplier;
        this.bufSize = IO.bufferSize;
        this.buffer = BufferUtil.allocate(this.bufSize);
    }

    @Override
    public void close() throws IOException
    {
        if (this.channel != null)
        {
            this.channel.close();
        }
    }

    @Override
    public void writeTo(OutputStream outputStream, long skipTo, long length) throws IOException
    {
        skipTo(skipTo);

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
            pos += readLen;
        }
    }

    private void skipTo(long skipTo) throws IOException
    {
        if (channel == null)
        {
            channel = channelSupplier.newSeekableByteChannel();
            pos = 0;
        }

        if (defaultSeekMode)
        {
            try
            {
                if (channel.position() != skipTo)
                {
                    channel.position(skipTo);
                    pos = skipTo;
                    return;
                }
            }
            catch (UnsupportedOperationException e)
            {
                defaultSeekMode = false;
                fallbackSkipTo(skipTo);
            }
        }
        else
        {
            // Fallback mode
            fallbackSkipTo(skipTo);
        }
    }

    private void fallbackSkipTo(long skipTo) throws IOException
    {
        if (skipTo < pos)
        {
            channel.close();
            channel = channelSupplier.newSeekableByteChannel();
            pos = 0;
        }

        if (pos < skipTo)
        {
            long skipSoFar = pos;
            long actualSkipped;
            int noProgressLoopLimit = NO_PROGRESS_LIMIT;
            // loop till we reach desired point, break out on lack of progress.
            while (noProgressLoopLimit > 0 && skipSoFar < skipTo)
            {
                BufferUtil.clearToFill(buffer);
                int len = (int)Math.min(bufSize, (skipTo - skipSoFar));
                buffer.limit(len);
                actualSkipped = channel.read(buffer);
                if (actualSkipped == 0)
                {
                    noProgressLoopLimit--;
                }
                else if (actualSkipped > 0)
                {
                    skipSoFar += actualSkipped;
                    noProgressLoopLimit = NO_PROGRESS_LIMIT;
                }
                else
                {
                    // negative values means the stream was closed or reached EOF
                    // either way, we've hit a state where we can no longer
                    // fulfill the requested range write.
                    throw new IOException("EOF reached before SeekableByteChannel skip destination");
                }
            }

            if (noProgressLoopLimit <= 0)
            {
                throw new IOException("No progress made to reach SeekableByteChannel skip position " + (skipTo - pos));
            }

            pos = skipTo;
        }
    }
}
