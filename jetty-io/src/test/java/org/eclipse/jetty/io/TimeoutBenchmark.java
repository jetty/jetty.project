//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;


import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class TimeoutBenchmark
{
    static Runnable NEVER = new Runnable()
    {
        @Override
        public void run()
        {    
            System.err.println("EXPIRED");
        }
    };

    @State(Scope.Benchmark)
    public static class SchedulerState
    {
        final ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
        volatile Scheduler.Task _task;

        @Setup
        public void setup() throws Exception
        {
            _timer.start();
            _task = _timer.schedule(NEVER,10,TimeUnit.SECONDS);
        }
        
        @TearDown
        public void tearDown() throws Exception
        {
            _task.cancel();
            _timer.stop();
        }
    }
    
    @Benchmark
    public void benchmarkScheduler(SchedulerState state)
    {
        state._task.cancel();
        state._task = state._timer.schedule(NEVER,10,TimeUnit.SECONDS);
    }
    
    @State(Scope.Benchmark)
    public static class VolatileState
    {
        volatile long _task;
        
        @Setup
        public void setup()
        {
            _task = System.nanoTime();
        }
    }    
    
    @Benchmark
    public void benchmarkVolatile(VolatileState state)
    {
        state._task = System.nanoTime();
    }
    
    public static void main(String[] args) throws Exception 
    {
        Options opt = new OptionsBuilder()
                .include(TimeoutBenchmark.class.getSimpleName())
                .warmupIterations(8)
                .measurementIterations(8)
                .threads(1)
                .forks(1)
                .build();

        new Runner(opt).run();
        
        Thread.sleep(20000);
    }

    
    
}
