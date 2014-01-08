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

package org.eclipse.jetty.monitor.integration;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/* ------------------------------------------------------------ */
/**
 * Derived from the JMX bean classes created by Kees Jan Koster for the java-monitor
 * J2EE probe http://code.google.com/p/java-monitor-probes/source/browse/.
 * 
 * @author kjkoster <kjkoster@gmail.com>
 */
@ManagedObject("Java Monitoring Tools")
public class JavaMonitorTools
{
    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    private static Method findDeadlockMethod = null;

    static
    {
        try
        {
            findDeadlockMethod = ThreadMXBean.class.getMethod("findDeadlockedThreads");
        }
        catch (Exception ignored)
        {
            // this is a 1.5 JVM
            try
            {
                findDeadlockMethod = ThreadMXBean.class.getMethod("findMonitorDeadlockedThreads");
            }
            catch (SecurityException e)
            {
                e.printStackTrace();
            }
            catch (NoSuchMethodException e)
            {
                e.printStackTrace();
            }
        }
    }

    private ThreadInfo[] findDeadlock()
        throws IllegalAccessException, InvocationTargetException
    {
        final long[] threadIds = (long[])findDeadlockMethod.invoke(threadMXBean,(Object[])null);

        if (threadIds == null || threadIds.length < 1)
        {
            // no deadlock, we're done
            return null;
        }

        final ThreadInfo[] threads = threadMXBean.getThreadInfo(threadIds,Integer.MAX_VALUE);
        return threads;
    }
    @ManagedOperation(value="Detailed report on the deadlocked threads.", impact="ACTION_INFO")
    public String getDeadlockStacktraces()
    {
        try
        {
            final ThreadInfo[] threads = findDeadlock();
            if (threads == null)
            {
                // no deadlock, we're done
                return null;
            }

            return stacktraces(threads,0);
        }
        catch (Exception e)
        {
            return e.getMessage();
        }
    }

    private static final int MAX_STACK = 10;

    private String stacktraces(final ThreadInfo[] threads, final int i)
    {
        if (i >= threads.length)
        {
            return "";
        }
        final ThreadInfo thread = threads[i];

        final StringBuilder trace = new StringBuilder();
        for (int stack_i = 0; stack_i < Math.min(thread.getStackTrace().length,MAX_STACK); stack_i++)
        {
            if (stack_i == (MAX_STACK - 1))
            {
                trace.append("    ...");
            }
            else
            {
                trace.append("    at ").append(thread.getStackTrace()[stack_i]).append("\n");
            }
        }

        return "\"" + thread.getThreadName() + "\", id " + thread.getThreadId() + " is " + thread.getThreadState() + " on " + thread.getLockName()
                + ", owned by " + thread.getLockOwnerName() + ", id " + thread.getLockOwnerId() + "\n" + trace + "\n\n" + stacktraces(threads,i + 1);
    }

    /**
     * We keep track of the last time we sampled the thread states.
     * It is a crude optimization to avoid having to query for the
     * threads states very often.
     */
    private long lastSampled = 0L;

    private final Map<Thread.State, Integer> states = new HashMap<Thread.State, Integer>();

    @ManagedOperation(value="Number of Blocked Threads")
    public int getThreadsBlocked()
    {
        sampleThreads();

        return states.get(Thread.State.BLOCKED);
    }

    @ManagedOperation(value="Number of New Threads", impact="ACTION_INFO")
    public int getThreadsNew()
    {
        sampleThreads();

        return states.get(Thread.State.NEW);
    }
    
    @ManagedOperation(value="Number of Terminated Threads", impact="ACTION_INFO")
    public int getThreadsTerminated()
    {
        sampleThreads();

        return states.get(Thread.State.TERMINATED);
    }

    @ManagedOperation(value="Number of Sleeping and Waiting threads")
    public int getThreadsTimedWaiting()
    {
        sampleThreads();

        return states.get(Thread.State.TIMED_WAITING);
    }

    @ManagedOperation(value="Number of Waiting Threads", impact="ACTION_INFO")
    public int getThreadsWaiting()
    {
        sampleThreads();

        return states.get(Thread.State.WAITING);
    }

    @ManagedOperation(value="Number of Runnable Threads", impact="ACTION_INFO")
    public int getThreadsRunnable()
    {
        sampleThreads();

        return states.get(Thread.State.RUNNABLE);
    }

    private synchronized void sampleThreads()
    {
        if ((lastSampled + 50L) < System.currentTimeMillis())
        {
            lastSampled = System.currentTimeMillis();
            for (final Thread.State state : Thread.State.values())
            {
                states.put(state,0);
            }

            for (final ThreadInfo thread : threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds()))
            {
                if (thread != null)
                {
                    final Thread.State state = thread.getThreadState();
                    states.put(state,states.get(state) + 1);
                }
                else
                {
                    states.put(Thread.State.TERMINATED,states.get(Thread.State.TERMINATED) + 1);
                }
            }
        }
    }

    private static final String POLICY = "sun.net.InetAddressCachePolicy";

    @ManagedOperation(value="Amount of time successful DNS queries are cached for.")
    public int getCacheSeconds() throws ClassNotFoundException,
            IllegalAccessException, InvocationTargetException,
            NoSuchMethodException {
        final Class policy = Class.forName(POLICY);
        final Object returnValue = policy.getMethod("get", (Class[]) null)
                .invoke(null, (Object[]) null);
        Integer seconds = (Integer) returnValue;

        return seconds.intValue();
    }

    @ManagedOperation(value="Amount of time failed DNS queries are cached for")
    public int getCacheNegativeSeconds() throws ClassNotFoundException,
            IllegalAccessException, InvocationTargetException,
            NoSuchMethodException {
        final Class policy = Class.forName(POLICY);
        final Object returnValue = policy.getMethod("getNegative",
                (Class[]) null).invoke(null, (Object[]) null);
        Integer seconds = (Integer) returnValue;

        return seconds.intValue();
    }

    private static final String DEFAULT = "default";

    private static final String SECURITY = "security";

    private static final String SYSTEM = "system";

    private static final String BOTH = "both";

    private static final String SECURITY_TTL = "networkaddress.cache.ttl";

    private static final String SYSTEM_TTL = "sun.net.inetaddr.ttl";

    private static final String SECURITY_NEGATIVE_TTL = "networkaddress.cache.negative.ttl";

    private static final String SYSTEM_NEGATIVE_TTL = "sun.net.inetaddr.negative.ttl";

    @ManagedOperation(value="Cache policy for successful DNS lookups was changed from the hard-coded default")
    public String getCacheTweakedFrom() {
        if (Security.getProperty(SECURITY_TTL) != null) {
            if (System.getProperty(SYSTEM_TTL) != null) {
                return BOTH;
            }

            return SECURITY;
        }

        if (System.getProperty(SYSTEM_TTL) != null) {
            return SYSTEM;
        }

        return DEFAULT;
    }

    @ManagedOperation(value="Cache policy for failed DNS lookups was changed from the hard-coded default")
    public String getCacheNegativeTweakedFrom() {
        if (Security.getProperty(SECURITY_NEGATIVE_TTL) != null) {
            if (System.getProperty(SYSTEM_NEGATIVE_TTL) != null) {
                return BOTH;
            }

            return SECURITY;
        }

        if (System.getProperty(SYSTEM_NEGATIVE_TTL) != null) {
            return SYSTEM;
        }

        return DEFAULT;
    }
}
