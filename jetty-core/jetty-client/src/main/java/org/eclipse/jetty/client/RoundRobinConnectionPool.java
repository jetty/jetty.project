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
 * <p>A {@link ConnectionPool} that attempts to provide connections using a round-robin algorithm.</p>
 * <p>The round-robin behavior is almost impossible to achieve for several reasons:</p>
 * <ul>
 *     <li>the server takes different times to serve different requests; if a request takes a long
 *     time to be processed by the server, it would be a performance penalty to stall sending requests
 *     waiting for that connection to be available - better skip it and try another connection</li>
 *     <li>connections may be closed by the client or by the server, so it would be a performance
 *     penalty to stall sending requests waiting for a new connection to be opened</li>
 *     <li>thread scheduling on both client and server may temporarily penalize a connection</li>
 * </ul>
 * <p>Do not expect this class to provide connections in a perfect recurring sequence such as
 * {@code c0, c1, ..., cN-1, c0, c1, ..., cN-1, c0, c1, ...} because that is impossible to
 * achieve in a real environment.
 * This class will just attempt a best-effort to provide the connections in a sequential order,
 * but most likely the order will be quasi-random.</p>
 * <p>Applications using this class should {@link #preCreateConnections(int) pre-create}
 * the connections to ensure that they are already opened when the application starts to requests
 * them, otherwise the first connection that is opened may be used multiple times before the others
 * are opened, resulting in a behavior that is more random-like than more round-robin-like (and
 * that confirms that round-robin behavior is almost impossible to achieve).</p>
 *
 * @see RandomConnectionPool
 */
@ManagedObject
public class RoundRobinConnectionPool extends MultiplexConnectionPool
{
    public RoundRobinConnectionPool(Destination destination, int maxConnections)
    {
        this(destination, maxConnections, 1);
    }

    public RoundRobinConnectionPool(Destination destination, int maxConnections, int maxMultiplex)
    {
        super(destination, Pool.StrategyType.ROUND_ROBIN, maxConnections, false, maxMultiplex);
        // If there are queued requests and connections get
        // closed due to idle timeout or overuse, we want to
        // aggressively try to open new connections to replace
        // those that were closed to process queued requests.
        setMaximizeConnections(true);
    }
}
