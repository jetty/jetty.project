//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.nio.NetworkTrafficSelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;

/**
 * <p>A specialized version of {@link SelectChannelConnector} that supports {@link NetworkTrafficListener}s.</p>
 * <p>{@link NetworkTrafficListener}s can be added and removed dynamically before and after this connector has
 * been started without causing {@link ConcurrentModificationException}s.</p>
 */
public class NetworkTrafficSelectChannelConnector extends SelectChannelConnector
{
    private final List<NetworkTrafficListener> listeners = new CopyOnWriteArrayList<NetworkTrafficListener>();

    /**
     * @param listener the listener to add
     */
    public void addNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.add(listener);
    }

    /**
     * @param listener the listener to remove
     */
    public void removeNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key) throws IOException
    {
        NetworkTrafficSelectChannelEndPoint endPoint = new NetworkTrafficSelectChannelEndPoint(channel, selectSet, key, _maxIdleTime, listeners);
        endPoint.setConnection(selectSet.getManager().newConnection(channel,endPoint, key.attachment()));
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
