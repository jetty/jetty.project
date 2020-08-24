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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized version of {@link SocketChannelEndPoint} that supports {@link NetworkTrafficListener}s.</p>
 */
public class NetworkTrafficSocketChannelEndPoint extends SocketChannelEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(NetworkTrafficSocketChannelEndPoint.class);

    private final NetworkTrafficListener listener;

    public NetworkTrafficSocketChannelEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key, Scheduler scheduler, long idleTimeout, NetworkTrafficListener listener)
    {
        super(channel, selectSet, key, scheduler);
        setIdleTimeout(idleTimeout);
        this.listener = listener;
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int read = super.fill(buffer);
        notifyIncoming(buffer, read);
        return read;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        boolean flushed = true;
        for (ByteBuffer b : buffers)
        {
            if (b.hasRemaining())
            {
                int position = b.position();
                ByteBuffer view = b.slice();
                flushed = super.flush(b);
                int l = b.position() - position;
                view.limit(view.position() + l);
                notifyOutgoing(view);
                if (!flushed)
                    break;
            }
        }
        return flushed;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (listener != null)
        {
            try
            {
                listener.opened(getChannel().socket());
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }

    @Override
    public void onClose(Throwable failure)
    {
        super.onClose(failure);
        if (listener != null)
        {
            try
            {
                listener.closed(getChannel().socket());
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }

    public void notifyIncoming(ByteBuffer buffer, int read)
    {
        if (listener != null && read > 0)
        {
            try
            {
                ByteBuffer view = buffer.asReadOnlyBuffer();
                listener.incoming(getChannel().socket(), view);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }

    public void notifyOutgoing(ByteBuffer view)
    {
        if (listener != null && view.hasRemaining())
        {
            try
            {
                listener.outgoing(getChannel().socket(), view);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }
}
