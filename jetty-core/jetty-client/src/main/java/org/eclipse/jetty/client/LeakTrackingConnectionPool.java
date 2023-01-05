//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeakTrackingConnectionPool extends DuplexConnectionPool
{
    private static final Logger LOG = LoggerFactory.getLogger(LeakTrackingConnectionPool.class);

    private final LeakDetector<Connection> leakDetector = new LeakDetector<>()
    {
        @Override
        protected void leaked(LeakInfo leakInfo)
        {
            LeakTrackingConnectionPool.this.leaked(leakInfo);
        }
    };

    public LeakTrackingConnectionPool(Destination destination, int maxConnections)
    {
        super(destination, maxConnections);
        addBean(leakDetector);
    }

    @Override
    public void close()
    {
        super.close();
        LifeCycle.stop(this);
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

    protected void leaked(LeakDetector<Connection>.LeakInfo leakInfo)
    {
        LOG.info("Connection {} leaked at:", leakInfo.getResourceDescription(), leakInfo.getStackFrames());
    }
}
