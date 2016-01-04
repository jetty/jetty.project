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

package org.eclipse.jetty.client;

public abstract class MultiplexHttpDestination extends HttpDestination
{
    protected MultiplexHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    protected ConnectionPool newConnectionPool(HttpClient client)
    {
        return new MultiplexConnectionPool(this, client.getMaxConnectionsPerDestination(), this,
                client.getMaxRequestsQueuedPerDestination());
    }

    public int getMaxRequestsPerConnection()
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof MultiplexConnectionPool)
            return ((MultiplexConnectionPool)connectionPool).getMaxMultiplex();
        return 1;
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof MultiplexConnectionPool)
            ((MultiplexConnectionPool)connectionPool).setMaxMultiplex(maxRequestsPerConnection);
    }
}
