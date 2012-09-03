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

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class ScheduledExecutionServiceScheduler extends AbstractLifeCycle implements Scheduler
{
    ScheduledExecutorService _service;
    
    public ScheduledExecutionServiceScheduler()
    {
    }
    
    @Override
    protected void doStart() throws Exception
    {
        _service=new ScheduledThreadPoolExecutor(1);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _service=null;
    }

    @Override
    public Task schedule(final Runnable task, final long delay, final TimeUnit units)
    {
        final Future<?> future = _service.schedule(task,delay,units);
        return new Task()
        {
            @Override
            public boolean cancel()
            {
                return future.cancel(true);
            }  
        };
    }
}
