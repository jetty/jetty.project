//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkHelper implements Runnable
{
    private final OperatingSystemMXBean operatingSystem;
    private final CompilationMXBean jitCompiler;
    private final MemoryMXBean heapMemory;
    private final AtomicInteger starts = new AtomicInteger();
    private final MemoryPoolMXBean youngMemoryPool;
    private final MemoryPoolMXBean survivorMemoryPool;
    private final MemoryPoolMXBean oldMemoryPool;
    private final boolean hasMemoryPools;
    private final GarbageCollectorMXBean youngCollector;
    private final GarbageCollectorMXBean oldCollector;
    private final boolean hasCollectors;
    private volatile ScheduledFuture<?> memoryPoller;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean polling;
    private volatile long lastYoungUsed;
    private volatile long startYoungCollections;
    private volatile long startYoungCollectionsTime;
    private volatile long totalYoungUsed;
    private volatile long lastSurvivorUsed;
    private volatile long totalSurvivorUsed;
    private volatile long lastOldUsed;
    private volatile long startOldCollections;
    private volatile long startOldCollectionsTime;
    private volatile long totalOldUsed;
    private volatile long startTime;
    private volatile long startProcessCPUTime;
    private volatile long startJITCTime;

    public BenchmarkHelper()
    {
        this.operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        this.jitCompiler = ManagementFactory.getCompilationMXBean();
        this.heapMemory = ManagementFactory.getMemoryMXBean();

        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        MemoryPoolMXBean ymp=null;
        MemoryPoolMXBean smp=null;
        MemoryPoolMXBean omp=null;
        
        for (MemoryPoolMXBean memoryPool : memoryPools)
        {
            if ("PS Eden Space".equals(memoryPool.getName()) ||
                    "Par Eden Space".equals(memoryPool.getName()) ||
                    "G1 Eden".equals(memoryPool.getName()))
                ymp = memoryPool;
            else if ("PS Survivor Space".equals(memoryPool.getName()) ||
                    "Par Survivor Space".equals(memoryPool.getName()) ||
                    "G1 Survivor".equals(memoryPool.getName()))
                smp = memoryPool;
            else if ("PS Old Gen".equals(memoryPool.getName()) ||
                    "CMS Old Gen".equals(memoryPool.getName()) ||
                    "G1 Old Gen".equals(memoryPool.getName()))
                omp = memoryPool;
        }
        youngMemoryPool=ymp;
        survivorMemoryPool=smp;
        oldMemoryPool=omp;
        
        hasMemoryPools = youngMemoryPool != null && survivorMemoryPool != null && oldMemoryPool != null;

        List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
        GarbageCollectorMXBean yc=null;
        GarbageCollectorMXBean oc=null;
        for (GarbageCollectorMXBean garbageCollector : garbageCollectors)
        {
            if ("PS Scavenge".equals(garbageCollector.getName()) ||
                    "ParNew".equals(garbageCollector.getName()) ||
                    "G1 Young Generation".equals(garbageCollector.getName()))
                yc = garbageCollector;
            else if ("PS MarkSweep".equals(garbageCollector.getName()) ||
                    "ConcurrentMarkSweep".equals(garbageCollector.getName()) ||
                    "G1 Old Generation".equals(garbageCollector.getName()))
                oc = garbageCollector;
        }
        youngCollector=yc;
        oldCollector=oc;
        hasCollectors = youngCollector != null && oldCollector != null;
    }

    public void run()
    {
        if (!hasMemoryPools)
            return;

        long young = youngMemoryPool.getUsage().getUsed();
        long survivor = survivorMemoryPool.getUsage().getUsed();
        long old = oldMemoryPool.getUsage().getUsed();

        if (!polling)
        {
            polling = true;
        }
        else
        {
            if (lastYoungUsed <= young)
            {
                totalYoungUsed += young - lastYoungUsed;
            }

            if (lastSurvivorUsed <= survivor)
            {
                totalSurvivorUsed += survivor - lastSurvivorUsed;
            }

            if (lastOldUsed <= old)
            {
                totalOldUsed += old - lastOldUsed;
            }
            else
            {
                // May need something more here, like "how much was collected"
            }
        }
        lastYoungUsed = young;
        lastSurvivorUsed = survivor;
        lastOldUsed = old;
    }

    public boolean startStatistics()
    {
        // Support for multiple nodes requires to ignore start requests after the first
        // but also requires that requests after the first wait until the initialization
        // is completed (otherwise node #2 may start the run while the server is GC'ing)
        synchronized (this)
        {
            if (starts.incrementAndGet() > 1)
                return false;

            System.gc();
            System.err.println("\n========================================");
            System.err.println("Statistics Started at " + new Date());
            System.err.println("Operative System: " + operatingSystem.getName() + " " + operatingSystem.getVersion() + " " + operatingSystem.getArch());
            System.err.println("JVM : " + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name") + " runtime " + System.getProperty("java.vm.version") + " " + System.getProperty("java.runtime.version"));
            System.err.println("Processors: " + operatingSystem.getAvailableProcessors());
            if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean)
            {
                com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)operatingSystem;
                long totalMemory = os.getTotalPhysicalMemorySize();
                long freeMemory = os.getFreePhysicalMemorySize();
                System.err.println("System Memory: " + percent(totalMemory - freeMemory, totalMemory) + "% used of " + gibiBytes(totalMemory) + " GiB");
            }
            else
            {
                System.err.println("System Memory: N/A");
            }

            MemoryUsage heapMemoryUsage = heapMemory.getHeapMemoryUsage();
            System.err.println("Used Heap Size: " + mebiBytes(heapMemoryUsage.getUsed()) + " MiB");
            System.err.println("Max Heap Size: " + mebiBytes(heapMemoryUsage.getMax()) + " MiB");
            if (hasMemoryPools)
            {
                long youngGenerationHeap = heapMemoryUsage.getMax() - oldMemoryPool.getUsage().getMax();
                System.err.println("Young Generation Heap Size: " + mebiBytes(youngGenerationHeap) + " MiB");
            }
            else
            {
                System.err.println("Young Generation Heap Size: N/A");
            }
            System.err.println("- - - - - - - - - - - - - - - - - - - - ");

            scheduler = Executors.newSingleThreadScheduledExecutor();
            polling = false;
            memoryPoller = scheduler.scheduleWithFixedDelay(this, 0, 250, TimeUnit.MILLISECONDS);

            lastYoungUsed = 0;
            if (hasCollectors)
            {
                startYoungCollections = youngCollector.getCollectionCount();
                startYoungCollectionsTime = youngCollector.getCollectionTime();
            }
            totalYoungUsed = 0;
            lastSurvivorUsed = 0;
            totalSurvivorUsed = 0;
            lastOldUsed = 0;
            if (hasCollectors)
            {
                startOldCollections = oldCollector.getCollectionCount();
                startOldCollectionsTime = oldCollector.getCollectionTime();
            }
            totalOldUsed = 0;

            startTime = System.nanoTime();
            if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean)
            {
                com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)operatingSystem;
                startProcessCPUTime = os.getProcessCpuTime();
            }
            startJITCTime = jitCompiler.getTotalCompilationTime();

            return true;
        }
    }

    public boolean stopStatistics()
    {
        synchronized (this)
        {
            if (starts.decrementAndGet() > 0)
                return false;

            memoryPoller.cancel(false);
            scheduler.shutdown();

            System.err.println("- - - - - - - - - - - - - - - - - - - - ");
            System.err.println("Statistics Ended at " + new Date());
            long elapsedTime = System.nanoTime() - startTime;
            System.err.println("Elapsed time: " + TimeUnit.NANOSECONDS.toMillis(elapsedTime) + " ms");
            long elapsedJITCTime = jitCompiler.getTotalCompilationTime() - startJITCTime;
            System.err.println("\tTime in JIT compilation: " + elapsedJITCTime + " ms");
            if (hasCollectors)
            {
                long elapsedYoungCollectionsTime = youngCollector.getCollectionTime() - startYoungCollectionsTime;
                long youngCollections = youngCollector.getCollectionCount() - startYoungCollections;
                System.err.println("\tTime in Young Generation GC: " + elapsedYoungCollectionsTime + " ms (" + youngCollections + " collections)");
                long elapsedOldCollectionsTime = oldCollector.getCollectionTime() - startOldCollectionsTime;
                long oldCollections = oldCollector.getCollectionCount() - startOldCollections;
                System.err.println("\tTime in Old Generation GC: " + elapsedOldCollectionsTime + " ms (" + oldCollections + " collections)");
            }
            else
            {
                System.err.println("\tTime in GC: N/A");
            }

            if (hasMemoryPools)
            {
                System.err.println("Garbage Generated in Young Generation: " + mebiBytes(totalYoungUsed) + " MiB");
                System.err.println("Garbage Generated in Survivor Generation: " + mebiBytes(totalSurvivorUsed) + " MiB");
                System.err.println("Garbage Generated in Old Generation: " + mebiBytes(totalOldUsed) + " MiB");
            }
            else
            {
                System.err.println("Garbage Generated: N/A");
            }

            if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean)
            {
                com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)operatingSystem;
                long elapsedProcessCPUTime = os.getProcessCpuTime() - startProcessCPUTime;
                System.err.println("Average CPU Load: " + ((float)elapsedProcessCPUTime * 100 / elapsedTime) + "/" + (100 * operatingSystem.getAvailableProcessors()));
            }
            else
            {
                System.err.println("Average CPU Load: N/A");
            }

            System.err.println("----------------------------------------\n");
            return true;
        }
    }

    public float percent(long dividend, long divisor)
    {
        return (float)dividend * 100 / divisor;
    }

    public float mebiBytes(long bytes)
    {
        return (float)bytes / 1024 / 1024;
    }

    public float gibiBytes(long bytes)
    {
        return (float)bytes / 1024 / 1024 / 1024;
    }
}
