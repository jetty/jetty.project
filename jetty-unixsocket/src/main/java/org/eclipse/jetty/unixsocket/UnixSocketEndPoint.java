//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.unixsocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;

import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

import jnr.unixsocket.UnixSocketChannel;

public class UnixSocketEndPoint extends ChannelEndPoint
{
    public final static InetSocketAddress NOIP=new InetSocketAddress(0);
    private static final Logger LOG = Log.getLogger(UnixSocketEndPoint.class);

    private final UnixSocketChannel _channel;
    
    public UnixSocketEndPoint(UnixSocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
    {
        super(channel,selector,key,scheduler);
        _channel=channel;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    
    @Override
    protected void doShutdownOutput()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("oshut {}", this);
        try
        {
            _channel.shutdownOutput();
            super.doShutdownOutput();
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
    }
}
