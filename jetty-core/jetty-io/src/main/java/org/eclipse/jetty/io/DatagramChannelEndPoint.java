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
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link EndPoint} implementation based on {@link DatagramChannel}.</p>
 */
public class DatagramChannelEndPoint extends SelectableChannelEndPoint
{
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

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        try
        {
            return getChannel().getRemoteAddress();
        }
        catch (Exception e)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("ignored", e);
        }
        return null;
    }

    @Override
    public SocketAddress receive(WritableBuffer buffer) throws IOException
    {
        if (isInputShutdown())
            return EOF;

        SocketAddress[] peers = new SocketAddress[1];
        long filled = buffer.readFrom(output ->
        {
            peers[0] = getChannel().receive(output);
            return false;
        });
        SocketAddress peer = peers[0];
        if (peer == null)
            return null;

        notIdle();

        if (LOG.isDebugEnabled())
            LOG.debug("filled {} {}", filled, buffer);
        return peer;
    }

    @Override
    public boolean send(SocketAddress address, ReadableBuffer buffer) throws IOException
    {
        long toSend = buffer.remaining();
        long flushed;
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing {} to {}", buffer, address);
            flushed = buffer.writeTo(input -> getChannel().send(input, address));
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} byte(s) - {}", flushed, this);
        }
        catch (IOException e)
        {
            throw new EofException(e);
        }

        if (flushed > 0)
            notIdle();

        return flushed == toSend;
    }

    @Override
    public void write(ReadableBuffer buffer, SocketAddress address, Callback callback) throws WritePendingException
    {
        getWriteFlusher().write(buffer, address, callback);
    }
}
