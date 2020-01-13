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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public class SocketChannelEndPoint extends ChannelEndPoint
{
    private static final Logger LOG = Log.getLogger(SocketChannelEndPoint.class);
    private final Socket _socket;
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;

    public SocketChannelEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        this((SocketChannel)channel, selector, key, scheduler);
    }

    public SocketChannelEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(channel, selector, key, scheduler);

        _socket = channel.socket();
        _local = (InetSocketAddress)_socket.getLocalSocketAddress();
        _remote = (InetSocketAddress)_socket.getRemoteSocketAddress();
    }

    public Socket getSocket()
    {
        return _socket;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _local;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _remote;
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            if (!_socket.isOutputShutdown())
                _socket.shutdownOutput();
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
    }
}
