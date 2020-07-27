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

package org.eclipse.jetty.util.thread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ShutdownThread is a shutdown hook thread implemented as
 * singleton that maintains a list of lifecycle instances
 * that are registered with it and provides ability to stop
 * these lifecycles upon shutdown of the Java Virtual Machine
 */
public class ShutdownThread extends Thread
{
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownThread.class);
    private static final ShutdownThread INSTANCE = new ShutdownThread();

    private final AutoLock _lock = new AutoLock();
    private final List<LifeCycle> _lifeCycles = new CopyOnWriteArrayList<>();
    private boolean _hooked;

    /**
     * Default constructor for the singleton
     *
     * Registers the instance as shutdown hook with the Java Runtime
     */
    private ShutdownThread()
    {
        super("JettyShutdownThread");
    }

    private void hook()
    {
        try (AutoLock ignored = _lock.lock())
        {
            if (!_hooked)
                Runtime.getRuntime().addShutdownHook(this);
            _hooked = true;
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
            LOG.info("shutdown already commenced");
        }
    }

    private void unhook()
    {
        try (AutoLock ignored = _lock.lock())
        {
            _hooked = false;
            Runtime.getRuntime().removeShutdownHook(this);
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
            LOG.debug("shutdown already commenced");
        }
    }

    /**
     * @return the singleton instance of the ShutdownThread
     */
    public static ShutdownThread getInstance()
    {
        return INSTANCE;
    }

    public static void register(LifeCycle... lifeCycles)
    {
        try (AutoLock ignored = INSTANCE._lock.lock())
        {
            INSTANCE._lifeCycles.addAll(Arrays.asList(lifeCycles));
            if (INSTANCE._lifeCycles.size() > 0)
                INSTANCE.hook();
        }
    }

    public static void register(int index, LifeCycle... lifeCycles)
    {
        try (AutoLock ignored = INSTANCE._lock.lock())
        {
            INSTANCE._lifeCycles.addAll(index, Arrays.asList(lifeCycles));
            if (INSTANCE._lifeCycles.size() > 0)
                INSTANCE.hook();
        }
    }

    public static void deregister(LifeCycle lifeCycle)
    {
        try (AutoLock ignored = INSTANCE._lock.lock())
        {
            INSTANCE._lifeCycles.remove(lifeCycle);
            if (INSTANCE._lifeCycles.size() == 0)
                INSTANCE.unhook();
        }
    }

    public static boolean isRegistered(LifeCycle lifeCycle)
    {
        return INSTANCE._lock.runLocked(() -> INSTANCE._lifeCycles.contains(lifeCycle));
    }

    @Override
    public void run()
    {
        List<LifeCycle> lifeCycles = INSTANCE._lock.runLocked(() -> new ArrayList<>(INSTANCE._lifeCycles));
        for (LifeCycle lifeCycle : lifeCycles)
        {
            try
            {
                if (lifeCycle.isStarted())
                {
                    lifeCycle.stop();
                    LOG.debug("Stopped {}", lifeCycle);
                }

                if (lifeCycle instanceof Destroyable)
                {
                    ((Destroyable)lifeCycle).destroy();
                    LOG.debug("Destroyed {}", lifeCycle);
                }
            }
            catch (Exception ex)
            {
                LOG.debug("Unable to stop", ex);
            }
        }
    }
}
