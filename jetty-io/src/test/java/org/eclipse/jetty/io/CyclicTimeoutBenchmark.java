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
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class CyclicTimeoutBenchmark
{
    final static int INSTANCES=10000;
    final static int SPACE=64;
    final static AtomicLong INDEX = new AtomicLong();
            
    static Runnable NEVER = () ->
    {
        throw new IllegalStateException("Should never expire!");
    };

    @State(Scope.Benchmark)
    public static class SchedulerState
    {
        final ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
        volatile Scheduler.Task[] _task;

        @Setup
        public void setup() throws Exception
        {
            INDEX.set(0);
            _timer.start();
            _task = new Scheduler.Task[INSTANCES*SPACE];
            for (int i=0;i<_task.length; i+=SPACE)
            {
                _task[i] = _timer.schedule(NEVER,10,TimeUnit.SECONDS); 
            }
        }
        
        @TearDown
        public void tearDown() throws Exception
        {
            for (int i=0;i<_task.length; i+=SPACE)
                _task[i].cancel();
            _timer.stop();
        }
    }
    
    @Benchmark
    public void benchmarkScheduler(SchedulerState state)
    {
        int instance = (int)((INDEX.incrementAndGet()%INSTANCES)*SPACE);
        state._task[instance].cancel();
        state._task[instance] = state._timer.schedule(NEVER,10,TimeUnit.SECONDS);
    }

    @State(Scope.Benchmark)
    public static class SchedulerXState
    {
        long pad0;
        long pad1;
        long pad2;
        long pad3;
        long pad4;
        long pad5;
        long pad6;
        long pad7;
        
        ScheduledExecutorScheduler[] _timer;

        long pad8;
        long pad9;
        long padA;
        long padB;
        long padC;
        long padD;
        long padE;
        long padF;
        
        volatile Scheduler.Task[] _task;
        
        long padG;
        long padH;
        long padI;
        long padJ;
        long padK;
        long padL;
        long padM;
        long padN;

        @Setup
        public void setup() throws Exception
        {
            INDEX.set(0);
            
            _timer = new ScheduledExecutorScheduler[8*SPACE];
            for (int i=0; i<_timer.length; i+=SPACE)
            {
                _timer[i] = new ScheduledExecutorScheduler();
                _timer[i].start();
            }

            
            _task = new Scheduler.Task[INSTANCES*SPACE];
            for (int i=0;i<_task.length; i+=SPACE)
            {
                _task[i] = _timer[(i%8)*SPACE].schedule(NEVER,10,TimeUnit.SECONDS); 
            }
        }
        
        @TearDown
        public void tearDown() throws Exception
        {
            for (int i=0;i<_task.length; i+=SPACE)
                _task[i].cancel();

            for (int i=0; i<_timer.length; i+=SPACE)
                _timer[i].stop();
        }
    }
    
    @Benchmark
    public void benchmarkSchedulerX(SchedulerXState state)
    {
        int instance = (int)((INDEX.incrementAndGet()%INSTANCES)*SPACE);
        state._task[instance].cancel();
        state._task[instance] = state._timer[(instance%8)*SPACE].schedule(NEVER,10,TimeUnit.SECONDS);
    }

    @State(Scope.Benchmark)
    public static class NonBlockingState
    {
        long pad0;
        long pad1;
        long pad2;
        long pad3;
        long pad4;
        long pad5;
        long pad6;
        long pad7;
        
        final ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();

        long pad8;
        long pad9;
        long padA;
        long padB;
        long padC;
        long padD;
        long padE;
        long padF;
        
        CyclicTimeout[] _task;
        
        long padG;
        long padH;
        long padI;
        long padJ;
        long padK;
        long padL;
        long padM;
        long padN;
        
        @Setup
        public void setup() throws Exception
        {
            INDEX.set(0);
            _timer.start();
            _task = new CyclicTimeout[INSTANCES*SPACE];
            for (int i=0;i<_task.length; i+=SPACE)
            {
                _task[i] = new CyclicTimeout(_timer)
                {
                    @Override
                    public void onTimeoutExpired()
                    {
                        throw new IllegalStateException("Should never expire!");
                    }
                };
                _task[i].schedule(10,TimeUnit.SECONDS);
            }
        }
        
        @TearDown
        public void tearDown() throws Exception
        {
            for (int i=0;i<_task.length; i+=SPACE)
                _task[i].cancel();
            _timer.stop();
        }
    }
    
    @Benchmark
    public void benchmarkNonBlocking(NonBlockingState state)
    {
        int instance = (int)((INDEX.incrementAndGet()%INSTANCES)*SPACE);
        state._task[instance].schedule(10,TimeUnit.SECONDS);
    }
    
    public static void main(String[] args) throws RunnerException 
    {
        Options opt = new OptionsBuilder()
                .include(CyclicTimeoutBenchmark.class.getSimpleName())
                .warmupIterations(8)
                .measurementIterations(8)
                .threads(64)
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}
