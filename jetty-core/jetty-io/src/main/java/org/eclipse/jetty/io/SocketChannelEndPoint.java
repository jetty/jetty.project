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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation based on {@link SocketChannel}.</p>
 */
public class SocketChannelEndPoint extends SelectableChannelEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(SocketChannelEndPoint.class);

    public SocketChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(scheduler, channel, selector, key);
    }

    @Override
    public SocketChannel getChannel()
    {
        return (SocketChannel)super.getChannel();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        try
        {
            return getChannel().getRemoteAddress();
        }
        catch (Throwable x)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("Could not retrieve remote socket address", x);
            return null;
        }
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            getChannel().shutdownOutput();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not shutdown output for {}", getChannel(), x);
        }
    }

    @Override
    public int fill(WritableBuffer buffer) throws IOException
    {
        if (isInputShutdown())
            return -1;

        int filled;
        try
        {
            filled = (int)buffer.readFrom(output -> getChannel().read(output) == -1);
            if (filled > 0)
                notIdle();
            else if (filled == -1)
                shutdownInput();
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to shutdown input", e);
            shutdownInput();
            filled = -1;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} {}", filled, buffer);
        return filled;
    }

    @Override
    public boolean flush(ReadableBuffer buffer) throws IOException
    {
        long flushed;
        try
        {
            flushed = buffer.writeTo(new ReadableBuffer.GatheringTarget()
            {
                @Override
                public void write(ByteBuffer[] inputs) throws IOException
                {
                    getChannel().write(inputs);
                }

                @Override
                public void write(ByteBuffer input) throws IOException
                {
                    getChannel().write(input);
                }
            });
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} {}", flushed, this);
        }
        catch (IOException e)
        {
            throw new EofException(e);
        }

        if (flushed > 0)
            notIdle();

        return buffer.remaining() == 0L;
    }
}
