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

package org.eclipse.jetty.server.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.nio.NetworkTrafficSelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;

public class NetworkTrafficSelectChannelConnector extends SelectChannelConnector
{
    private final List<NetworkTrafficListener> listeners = new CopyOnWriteArrayList<NetworkTrafficListener>();

    public void addNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.add(listener);
    }

    public void removeNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key) throws IOException
    {
        NetworkTrafficSelectChannelEndPoint endPoint = new NetworkTrafficSelectChannelEndPoint(channel, selectSet, key, _maxIdleTime, listeners);
        endPoint.notifyOpened();
        return endPoint;
    }

    @Override
    protected void endPointClosed(SelectChannelEndPoint endpoint)
    {
        super.endPointClosed(endpoint);
        ((NetworkTrafficSelectChannelEndPoint)endpoint).notifyClosed();
    }
}
