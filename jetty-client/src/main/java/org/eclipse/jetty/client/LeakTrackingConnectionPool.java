//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.LeakDetector;

public class LeakTrackingConnectionPool extends ConnectionPool
{
    private final LeakDetector<Connection> leakDetector;

    public LeakTrackingConnectionPool(Destination destination, int maxConnections, Callback requester, LeakDetector<Connection> leakDetector)
    {
        super(destination, maxConnections, requester);
        this.leakDetector = leakDetector;
        start();
    }

    private void start()
    {
        try
        {
            leakDetector.start();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    @Override
    public void close()
    {
        stop();
        super.close();
    }

    private void stop()
    {
        try
        {
            leakDetector.stop();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    @Override
    protected void acquired(Connection connection)
    {
        if (!leakDetector.acquired(connection))
            LOG.info("Connection {}@{} not tracked", connection, System.identityHashCode(connection));
    }

    @Override
    protected void released(Connection connection)
    {
        if (!leakDetector.released(connection))
            LOG.info("Connection {}@{} released but not acquired", connection, System.identityHashCode(connection));
    }
}
