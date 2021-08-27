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

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Connection;

public abstract class MultiplexHttpDestination extends HttpDestination
{
    protected MultiplexHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    public int getMaxRequestsPerConnection()
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            return ((ConnectionPool.Multiplexable)connectionPool).getMaxMultiplex();
        return 1;
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        setMaxRequestsPerConnection(null, maxRequestsPerConnection);
    }

    public void setMaxRequestsPerConnection(Connection connection, int maxRequestsPerConnection)
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            ((ConnectionPool.Multiplexable)connectionPool).setMaxMultiplex(connection, maxRequestsPerConnection);
    }
}
