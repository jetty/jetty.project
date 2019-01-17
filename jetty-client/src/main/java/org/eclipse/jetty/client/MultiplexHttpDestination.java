//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.function.Function;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

public class MultiplexHttpDestination extends HttpDestination implements HttpDestination.Multiplexed
{
    public MultiplexHttpDestination(HttpClient client, Info info)
    {
        this(client, info, Function.identity());
    }

    public MultiplexHttpDestination(HttpClient client, Info info, Function<ClientConnectionFactory, ClientConnectionFactory> factoryFn)
    {
        super(client, info, factoryFn);
    }

    @ManagedAttribute(value = "The maximum number of concurrent requests per connection")
    public int getMaxRequestsPerConnection()
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            return ((ConnectionPool.Multiplexable)connectionPool).getMaxMultiplex();
        return 1;
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            ((ConnectionPool.Multiplexable)connectionPool).setMaxMultiplex(maxRequestsPerConnection);
    }
}
