//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A facility to detect improper usage of resource pools.
 * <p>
 * Resource pools usually have a method to acquire a pooled resource and a method to released it back to the pool.
 * <p>
 * To detect if client code acquires a resource but never releases it, the resource pool can be modified to use a
 * {@link LeakDetector}. The modified resource pool should call {@link #acquired(Object)} every time the method to
 * acquire a resource is called, and {@link #released(Object)} every time the method to release the resource is called.
 * {@link LeakDetector} keeps track of these resources and invokes method
 * {@link #leaked(org.eclipse.jetty.util.LeakDetector.LeakInfo)} when it detects that a resource has been leaked (that
 * is, acquired but never released).
 * <p>
 * To detect whether client code releases a resource without having acquired it, the resource pool can be modified to
 * check the return value of {@link #released(Object)}: if false, it means that the resource was not acquired.
 * <p>
 * IMPLEMENTATION NOTES
 * <p>
 * This class relies on {@link System#identityHashCode(Object)} to create a unique id for each resource passed to
 * {@link #acquired(Object)} and {@link #released(Object)}. {@link System#identityHashCode(Object)} does not guarantee
 * that it will not generate the same number for different objects, but in practice the chance of collision is rare.
 * <p>
 * {@link LeakDetector} uses {@link PhantomReference}s to detect leaks. {@link PhantomReference}s are enqueued in their
 * {@link ReferenceQueue} <em>after</em> they have been garbage collected (differently from {@link WeakReference}s that
 * are enqueued <em>before</em>). Since the resource is now garbage collected, {@link LeakDetector} checks whether it
 * has been released and if not, it reports a leak. Using {@link PhantomReference}s is better than overriding
 * {@link #finalize()} and works also in those cases where {@link #finalize()} is not overridable.
 *
 * @param <T> the resource type.
 */
public class LeakDetector<T> extends AbstractLifeCycle implements Runnable
{
    private static final Logger LOG = Log.getLogger(LeakDetector.class);

    private final ReferenceQueue<T> queue = new ReferenceQueue<>();
    private final ConcurrentMap<String, LeakInfo> resources = new ConcurrentHashMap<>();
    private Thread thread;

    /**
     * Tracks the resource as been acquired.
     *
     * @param resource the resource that has been acquired
     * @return true whether the resource has been acquired normally, false if the resource has detected a leak (meaning
     *         that another acquire occurred before a release of the same resource)
     * @see #released(Object)
     */
    public boolean acquired(T resource)
    {
        String id = id(resource);
        LeakInfo info = resources.putIfAbsent(id, new LeakInfo(resource,id));
        if (info != null)
        {
            // Leak detected, prior acquire exists (not released) or id clash.
            return false;
        }
        // Normal behavior.
        return true;
    }

    /**
     * Tracks the resource as been released.
     *
     * @param resource the resource that has been released
     * @return true whether the resource has been released normally (based on a previous acquire). false if the resource
     *         has been released without a prior acquire (such as a double release scenario)
     * @see #acquired(Object)
     */
    public boolean released(T resource)
    {
        String id = id(resource);
        LeakInfo info = resources.remove(id);
        if (info != null)
        {
            // Normal behavior.
            return true;
        }

        // Leak detected (released without acquire).
        return false;
    }

    /**
     * Generates a unique ID for the given resource.
     *
     * @param resource the resource to generate the unique ID for
     * @return the unique ID of the given resource
     */
    public String id(T resource)
    {
        return String.valueOf(System.identityHashCode(resource));
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        thread = new Thread(this,getClass().getSimpleName());
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        thread.interrupt();
    }

    @Override
    public void run()
    {
        try
        {
            while (isRunning())
            {
                @SuppressWarnings("unchecked")
                LeakInfo leakInfo = (LeakInfo)queue.remove();
                if (LOG.isDebugEnabled())
                    LOG.debug("Resource GC'ed: {}",leakInfo);
                if (resources.remove(leakInfo.id) != null)
                    leaked(leakInfo);
            }
        }
        catch (InterruptedException x)
        {
            // Exit
        }
    }

    /**
     * Callback method invoked by {@link LeakDetector} when it detects that a resource has been leaked.
     *
     * @param leakInfo the information about the leak
     */
    protected void leaked(LeakInfo leakInfo)
    {
        LOG.warn("Resource leaked: " + leakInfo.description,leakInfo.stackFrames);
    }

    /**
     * Information about the leak of a resource.
     */
    public class LeakInfo extends PhantomReference<T>
    {
        private final String id;
        private final String description;
        private final Throwable stackFrames;

        private LeakInfo(T referent, String id)
        {
            super(referent,queue);
            this.id = id;
            this.description = referent.toString();
            this.stackFrames = new Throwable();
        }

        /**
         * @return the resource description as provided by the resource's {@link Object#toString()} method.
         */
        public String getResourceDescription()
        {
            return description;
        }

        /**
         * @return a Throwable instance that contains the stack frames at the time of resource acquisition.
         */
        public Throwable getStackFrames()
        {
            return stackFrames;
        }

        @Override
        public String toString()
        {
            return description;
        }
    }
}
