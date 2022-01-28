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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation based on {@link DatagramChannel}.</p>
 */
public class DatagramChannelEndPoint extends SelectableChannelEndPoint
{
    public static final SocketAddress EOF = InetSocketAddress.createUnresolved("", 0);
    private static final Logger LOG = LoggerFactory.getLogger(DatagramChannelEndPoint.class);

    public DatagramChannelEndPoint(DatagramChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(scheduler, channel, selector, key);
    }

    @Override
    public DatagramChannel getChannel()
    {
        return (DatagramChannel)super.getChannel();
    }

    /**
     * <p>Receives data into the given buffer from the returned address.</p>
     * <p>This method should be used to receive UDP data.</p>
     *
     * @param buffer the buffer to fill with data
     * @return the peer address that sent the data
     * @throws IOException if the receive fails
     */
    public SocketAddress receive(ByteBuffer buffer) throws IOException
    {
        if (isInputShutdown())
            return EOF;

        int pos = BufferUtil.flipToFill(buffer);
        SocketAddress peer = getChannel().receive(buffer);
        BufferUtil.flipToFlush(buffer, pos);
        if (peer == null)
            return null;

        notIdle();

        int filled = buffer.remaining();
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} {}", filled, BufferUtil.toDetailString(buffer));
        return peer;
    }

    /**
     * <p>Sends to the given address the data in the given buffers.</p>
     * <p>This methods should be used to send UDP data.</p>
     *
     * @param address the peer address to send data to
     * @param buffers the buffers containing the data to send
     * @return true if all the buffers have been consumed
     * @throws IOException if the send fails
     * @see #write(Callback, SocketAddress, ByteBuffer...)
     */
    public boolean send(SocketAddress address, ByteBuffer... buffers) throws IOException
    {
        boolean flushedAll = true;
        long flushed = 0;
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing {} buffer(s) to {}", buffers.length, address);
            for (ByteBuffer buffer : buffers)
            {
                int sent = getChannel().send(buffer, address);
                if (sent == 0)
                {
                    flushedAll = false;
                    break;
                }
                flushed += sent;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} byte(s), all flushed? {} - {}", flushed, flushedAll, this);
        }
        catch (IOException e)
        {
            throw new EofException(e);
        }

        if (flushed > 0)
            notIdle();

        return flushedAll;
    }

    /**
     * <p>Writes to the given address the data contained in the given buffers, and invokes
     * the given callback when either all the data has been sent, or a failure occurs.</p>
     *
     * @param callback the callback to notify of the success or failure of the write operation
     * @param address the peer address to send data to
     * @param buffers the buffers containing the data to send
     * @throws WritePendingException if a previous write was initiated but was not yet completed
     * @see #send(SocketAddress, ByteBuffer...)
     */
    public void write(Callback callback, SocketAddress address, ByteBuffer... buffers) throws WritePendingException
    {
        getWriteFlusher().write(callback, address, buffers);
    }
}
