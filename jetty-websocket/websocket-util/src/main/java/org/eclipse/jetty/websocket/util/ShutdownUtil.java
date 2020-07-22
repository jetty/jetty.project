//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShutdownUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownUtil.class);

    /**
     * Shutdown a {@link LifeCycle} in a new daemon thread and be notified on the result in a {@link CompletableFuture}.
     * @param lifeCycle the LifeCycle to stop.
     * @return the CompletableFuture to be notified when the stop either completes or fails.
     */
    public static CompletableFuture<Void> shutdown(LifeCycle lifeCycle)
    {
        AtomicReference<Thread> stopThreadReference = new AtomicReference<>();
        CompletableFuture<Void> shutdown = new CompletableFuture<>()
        {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning)
            {
                boolean canceled = super.cancel(mayInterruptIfRunning);
                if (canceled && mayInterruptIfRunning)
                {
                    Thread thread = stopThreadReference.get();
                    if (thread != null)
                        thread.interrupt();
                }

                return canceled;
            }
        };

        Thread stopThread = new Thread(() ->
        {
            try
            {
                lifeCycle.stop();
                shutdown.complete(null);
            }
            catch (Throwable t)
            {
                LOG.warn("Error while stopping {}", lifeCycle, t);
                shutdown.completeExceptionally(t);
            }
        });
        stopThread.setDaemon(true);
        stopThreadReference.set(stopThread);
        stopThread.start();
        return shutdown;
    }
}
