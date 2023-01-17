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

import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * <p>A {@link ConnectionPool} that provides connections
 * randomly among the ones that are available.</p>
 */
@ManagedObject
public class RandomConnectionPool extends MultiplexConnectionPool
{
    public RandomConnectionPool(Destination destination, int maxConnections, int maxMultiplex)
    {
        super(destination, Pool.StrategyType.RANDOM, maxConnections, false, maxMultiplex);
    }
}
