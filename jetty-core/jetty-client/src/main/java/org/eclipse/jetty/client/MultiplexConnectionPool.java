//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.function.ToIntFunction;

import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class MultiplexConnectionPool extends AbstractConnectionPool
{
    /**
     * <p>Returns a function that computes the max multiplex value
     * for a given {@link Connection}, if possible, otherwise returns
     * the given {@code defaultMaxMultiplex} value.</p>
     *
     * @param defaultMaxMultiplex the default max multiplex value
     * @return a function that computes the max multiplex value for a connection
     */
    public static ToIntFunction<Connection> newMaxMultiplexer(int defaultMaxMultiplex)
    {
        return connection ->
        {
            int maxMultiplex = defaultMaxMultiplex;
            if (connection instanceof MaxMultiplexable maxMultiplexable)
                maxMultiplex = maxMultiplexable.getMaxMultiplex();
            return maxMultiplex;
        };
    }

    public MultiplexConnectionPool(Destination destination, int maxConnections, int initialMaxMultiplex)
    {
        this(destination, () -> new ConcurrentPool<>(ConcurrentPool.StrategyType.FIRST, maxConnections, false, newMaxMultiplexer(initialMaxMultiplex)), initialMaxMultiplex);
    }

    protected MultiplexConnectionPool(Destination destination, Pool.Factory<Connection> poolFactory, int initialMaxMultiplex)
    {
        super(destination, poolFactory, initialMaxMultiplex);
    }

    @Override
    @ManagedAttribute(value = "The initial multiplexing factor of connections")
    public int getInitialMaxMultiplex()
    {
        return super.getInitialMaxMultiplex();
    }

    @Override
    public void setInitialMaxMultiplex(int initialMaxMultiplex)
    {
        super.setInitialMaxMultiplex(initialMaxMultiplex);
    }
}
