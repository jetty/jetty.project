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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class DuplexConnectionPool extends AbstractConnectionPool
{
    public DuplexConnectionPool(HttpDestination destination, int maxConnections, Callback requester)
    {
        this(destination, maxConnections, false, requester);
    }

    public DuplexConnectionPool(HttpDestination destination, int maxConnections, boolean cache, Callback requester)
    {
        this(destination, new Pool<>(Pool.StrategyType.FIRST, maxConnections, cache), requester);
    }

    public DuplexConnectionPool(HttpDestination destination, Pool<Connection> pool, Callback requester)
    {
        super(destination, pool, requester);
    }

    @Override
    @ManagedAttribute(value = "The maximum amount of times a connection is used before it gets closed")
    public int getMaxUsageCount()
    {
        return super.getMaxUsageCount();
    }

    @Override
    public void setMaxUsageCount(int maxUsageCount)
    {
        super.setMaxUsageCount(maxUsageCount);
    }
}
