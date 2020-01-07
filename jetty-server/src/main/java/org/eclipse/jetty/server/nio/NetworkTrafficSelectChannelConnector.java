//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * @deprecated use {@link org.eclipse.jetty.server.NetworkTrafficServerConnector} instead.
 */
@Deprecated
public class NetworkTrafficSelectChannelConnector extends NetworkTrafficServerConnector
{
    public NetworkTrafficSelectChannelConnector(Server server)
    {
        super(server);
    }

    public NetworkTrafficSelectChannelConnector(Server server, ConnectionFactory connectionFactory, SslContextFactory sslContextFactory)
    {
        super(server, connectionFactory, sslContextFactory);
    }

    public NetworkTrafficSelectChannelConnector(Server server, ConnectionFactory connectionFactory)
    {
        super(server, connectionFactory);
    }

    public NetworkTrafficSelectChannelConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, int selectors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, acceptors, selectors, factories);
    }

    public NetworkTrafficSelectChannelConnector(Server server, SslContextFactory sslContextFactory)
    {
        super(server, sslContextFactory);
    }
}
