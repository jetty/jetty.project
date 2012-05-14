// ========================================================================
// Copyright (c) 2011 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NetworkTrafficSelectChannelEndPoint extends SelectChannelEndPoint
{
    private static final Logger LOG = Log.getLogger(NetworkTrafficSelectChannelEndPoint.class);

    private final List<NetworkTrafficListener> listeners;

    public NetworkTrafficSelectChannelEndPoint(SocketChannel channel, SelectorManager.ManagedSelector selectSet, SelectionKey key, int maxIdleTime, List<NetworkTrafficListener> listeners) throws IOException
    {
        super(channel, selectSet, key, maxIdleTime);
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
    public int flush(ByteBuffer... buffers) throws IOException
    {
        int written=0;
        for (ByteBuffer b : buffers)
        {
            if (b.hasRemaining())
            {
                int position = b.position();
                int l = super.flush(b);
                notifyOutgoing(b, position, l);
                if (l==0)
                    break;
                else
                    written+=l;
            }
        }
        return written;
    }


    public void notifyOpened()
    {
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
                    LOG.warn(x);
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
                    LOG.warn(x);
                }
            }
        }
    }

    public void notifyOutgoing(ByteBuffer buffer, int position, int written)
    {
        if (listeners != null && !listeners.isEmpty() && written > 0)
        {
            for (NetworkTrafficListener listener : listeners)
            {
                try
                {
                    ByteBuffer view = buffer.slice();
                    view.position(position);
                    view.limit(position + written);
                    listener.outgoing(getSocket(), view);
                }
                catch (Exception x)
                {
                    LOG.warn(x);
                }
            }
        }
    }

    public void notifyClosed()
    {
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
                    LOG.warn(x);
                }
            }
        }
    }
}
