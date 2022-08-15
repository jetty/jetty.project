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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class DuplexConnectionPool extends AbstractConnectionPool
{
    public DuplexConnectionPool(HttpDestination destination, int maxConnections, Callback requester)
    {
        super(destination, new Pool<>(Pool.StrategyType.FIRST, maxConnections, false), requester, 1);
    }
}
