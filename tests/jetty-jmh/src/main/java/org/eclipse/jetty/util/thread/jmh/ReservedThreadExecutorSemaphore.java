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

package org.eclipse.jetty.util.thread.jmh;

import java.util.concurrent.Executor;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;

public class ReservedThreadExecutorSemaphore extends ReservedThreadExecutor
{
    public ReservedThreadExecutorSemaphore(Executor executor, int capacity)
    {
        super(executor, capacity);
    }

    public ReservedThreadExecutorSemaphore(Executor executor, int capacity, int minSize)
    {
        super(executor, capacity, minSize);
    }

    public ReservedThreadExecutorSemaphore(Executor executor, int capacity, int minSize, int maxPending)
    {
        super(executor, capacity, minSize, maxPending);
    }
}
