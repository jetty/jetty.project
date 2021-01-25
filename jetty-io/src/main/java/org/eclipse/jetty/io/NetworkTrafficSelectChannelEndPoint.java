//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.io;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * @deprecated use {@link NetworkTrafficSocketChannelEndPoint} instead
 */
@Deprecated
public class NetworkTrafficSelectChannelEndPoint extends NetworkTrafficSocketChannelEndPoint
{
    public NetworkTrafficSelectChannelEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key, Scheduler scheduler, long idleTimeout, List<NetworkTrafficListener> listeners)
    {
        super(channel, selectSet, key, scheduler, idleTimeout, listeners);
    }
}
