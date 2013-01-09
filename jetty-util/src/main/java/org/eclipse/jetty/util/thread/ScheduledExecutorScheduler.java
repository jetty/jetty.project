//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class ScheduledExecutorScheduler extends AbstractLifeCycle implements Scheduler
{
    private volatile ScheduledThreadPoolExecutor scheduler;

    @Override
    protected void doStart() throws Exception
    {
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        scheduler.shutdownNow();
        super.doStop();
        scheduler = null;
    }

    @Override
    public Task schedule(Runnable task, long delay, TimeUnit unit)
    {
        ScheduledFuture<?> result = scheduler.schedule(task, delay, unit);
        return new ScheduledFutureTask(result);
    }

    private class ScheduledFutureTask implements Task
    {
        private final ScheduledFuture<?> scheduledFuture;

        public ScheduledFutureTask(ScheduledFuture<?> scheduledFuture)
        {
            this.scheduledFuture = scheduledFuture;
        }

        @Override
        public boolean cancel()
        {
            return scheduledFuture.cancel(false);
        }
    }
}
