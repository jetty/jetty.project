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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LeakTrackingConnectionPool extends DuplexConnectionPool
{
    private static final Logger LOG = Log.getLogger(LeakTrackingConnectionPool.class);

    private final LeakDetector<Connection> leakDetector = new LeakDetector<Connection>()
    {
        @Override
        protected void leaked(LeakInfo leakInfo)
        {
            LeakTrackingConnectionPool.this.leaked(leakInfo);
        }
    };

    public LeakTrackingConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        super(destination, maxConnections, requester);
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
            LOG.info("Connection {}@{} not tracked", connection, leakDetector.id(connection));
    }

    @Override
    protected void released(Connection connection)
    {
        if (!leakDetector.released(connection))
            LOG.info("Connection {}@{} released but not acquired", connection, leakDetector.id(connection));
    }

    protected void leaked(LeakDetector.LeakInfo leakInfo)
    {
        LOG.info("Connection " + leakInfo.getResourceDescription() + " leaked at:", leakInfo.getStackFrames());
    }
}
