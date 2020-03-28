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
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkTrafficSelectChannelEndPoint extends SocketChannelEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(NetworkTrafficSelectChannelEndPoint.class);

    private final List<NetworkTrafficListener> listeners;

    public NetworkTrafficSelectChannelEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key, Scheduler scheduler, long idleTimeout, List<NetworkTrafficListener> listeners) throws IOException
    {
        super(channel, selectSet, key, scheduler);
        setIdleTimeout(idleTimeout);
        this.listeners = listeners;
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
                flushed &= super.flush(b);
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
        if (listeners != null && !listeners.isEmpty())
        {
            for (NetworkTrafficListener listener : listeners)
            {
                try
                {
                    listener.opened(getSocket());
                }
                catch (Exception x)
                {
                    LOG.warn("listener.opened failure", x);
                }
            }
        }
    }

    @Override
    public void onClose(Throwable cause)
    {
        super.onClose(cause);
        if (listeners != null && !listeners.isEmpty())
        {
            for (NetworkTrafficListener listener : listeners)
            {
                try
                {
                    listener.closed(getSocket());
                }
                catch (Exception x)
                {
                    LOG.warn("listener.closed failure", x);
                }
            }
        }
    }

    public void notifyIncoming(ByteBuffer buffer, int read)
    {
        if (listeners != null && !listeners.isEmpty() && read > 0)
        {
            for (NetworkTrafficListener listener : listeners)
            {
                try
                {
                    ByteBuffer view = buffer.asReadOnlyBuffer();
                    listener.incoming(getSocket(), view);
                }
                catch (Exception x)
                {
                    LOG.warn("listener.incoming() failure", x);
                }
            }
        }
    }

    public void notifyOutgoing(ByteBuffer view)
    {
        if (listeners != null && !listeners.isEmpty() && view.hasRemaining())
        {
            Socket socket = getSocket();
            for (NetworkTrafficListener listener : listeners)
            {
                try
                {
                    listener.outgoing(socket, view);
                }
                catch (Exception x)
                {
                    LOG.warn("listener.outgoing() failure", x);
                }
            }
        }
    }
}
