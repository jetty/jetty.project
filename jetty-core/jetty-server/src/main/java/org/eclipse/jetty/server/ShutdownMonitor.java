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

package org.eclipse.jetty.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * Shutdown/Stop Monitor thread.
 * <p>
 * This thread listens on the host/port specified by the STOP.HOST/STOP.PORT
 * system parameter (defaults to 127.0.0.1/-1 for not listening) for request
 * authenticated with the key given by the STOP.KEY system parameter
 * for admin requests.
 * <p>
 * If the stop port is set to zero, then a random port is assigned and the
 * port number is printed to stdout.
 * <p>
 * Commands "stop" and "status" are currently supported.
 *
 * @deprecated Replaced with {@link ServerShutdown} component, which is not a singleton.
 */
@Deprecated(since = "12.0.23", forRemoval = true)
public class ShutdownMonitor extends ServerShutdown
{
    protected static AtomicReference<ShutdownMonitor> INSTANCE = new AtomicReference<>();

    /**
     * @deprecated No direct replacement, see {@link ServerShutdown}, which isn't a singleton.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public static ShutdownMonitor getInstance()
    {
        return INSTANCE.updateAndGet((h) -> h != null ? h : new ShutdownMonitor());
    }

    /**
     * This existed for test case reasons, it was never a public runtime method.
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    protected static void reset()
    {
        INSTANCE.set(null);
    }

    /**
     * @deprecated See {@link ServerShutdown#addComponent(LifeCycle)}.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public static void register(LifeCycle... lifeCycles)
    {
        getInstance().addLifeCycles(lifeCycles);
    }

    /**
     * @deprecated See {@link ServerShutdown#removeComponent(LifeCycle)}
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public static void deregister(LifeCycle lifeCycle)
    {
        getInstance().removeLifeCycle(lifeCycle);
    }

    /**
     * This existed for test case reasons, it was never a public runtime method.
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public static boolean isRegistered(LifeCycle lifeCycle)
    {
        return getInstance().containsLifeCycle(lifeCycle);
    }

    /**
     * <p>
     * Creates a ShutdownMonitor using configuration from the System properties.
     * </p>
     *
     * <dl>
     * <dt>{@code STOP.HOST}</dt>
     * <dd>IP to listen on, defaults to {@code 127.0.0.1}</dd>
     * <dt>{@code STOP.PORT}</dt>
     * <dd>Port to listen on, defaults to {@code 0}.
     * (0 will use a port number that is automatically allocated)</dd>
     * <dt>{@code STOP.KEY}</dt>
     * <dd>The Key that must be provided to initiate a Shutdown</dd>
     * <dt>{@code STOP.EXIT}</dt>
     * <dd>Boolean to indicate if a {@code System.exit(0)} should occur on successful shutdown,
     * defaults to {@code true}</dd>
     * <ul>
     */
    private ShutdownMonitor()
    {
        super();
    }

    protected int getRegisteredLifeCycleCount()
    {
        try (AutoLock l = lock.lock())
        {
            return components.size();
        }
    }

    private void addLifeCycles(LifeCycle... lifeCycles)
    {
        try (AutoLock l = lock.lock())
        {
            for (LifeCycle lifeCycle : lifeCycles)
            {
                addComponent(lifeCycle);
            }
        }
    }

    /**
     * Does nothing.
     */
    private void removeLifeCycle(LifeCycle lifeCycle)
    {
        removeComponent(lifeCycle);
    }

    private boolean containsLifeCycle(LifeCycle lifeCycle)
    {
        return hasComponent(lifeCycle);
    }

    /**
     * Does nothing.
     *
     * @deprecated No replacement, use SLF4J Logger at name {@link org.eclipse.jetty.server.ServerShutdown}
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public void setDebug(boolean flag)
    {
        // does nothing
    }

    /**
     * Does nothing.
     * This existed for test case reasons, it was never a public runtime method.
     *
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public void setExitVm(boolean exitVm)
    {
        // does nothing
    }

    @Override
    public int getPort()
    {
        // Backward compat behavior
        if (isListening())
            return super.getLocalPort();
        else
            return super.getPort();
    }

    /**
     * Does nothing.
     * This existed for test case reasons, it was never a public runtime method.
     *
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    public void await() throws InterruptedException
    {
        throw new UnsupportedOperationException("await() no longer supported");
    }

    /**
     * Does nothing.
     * This existed for test case reasons, it was never a public runtime method.
     *
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.0.23", forRemoval = true)
    protected boolean await(long time, TimeUnit unit) throws InterruptedException
    {
        throw new UnsupportedOperationException("await(long, TimeUnit) no longer supported");
    }

    protected boolean isAlive()
    {
        return isListening();
    }
}
