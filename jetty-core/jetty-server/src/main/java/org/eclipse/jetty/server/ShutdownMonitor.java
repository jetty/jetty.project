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

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shutdown Monitor.
 *
 * <p>
 *     This is a singleton that is only valid when the following System Properties are defined.
 * </p>
 *
 * <dl>
 * <dt>{@code STOP.HOST}</dt>
 * <dd>IP to listen on, defaults to {@code 127.0.0.1}</dd>
 * <dt>{@code STOP.PORT}</dt>
 * <dd>Port to listen on, defaults to {@code -1} (or disabled).<br>
 * (0 will use a port number that is automatically allocated)</dd>
 * <dt>{@code STOP.KEY}</dt>
 * <dd>The Key that must be provided to initiate a Shutdown.<br>
 * Limited to {@link java.nio.charset.StandardCharsets#US_ASCII US_ASCII} charset.<br>
 * If one is not provided, a generated Key will be created.</dd>
 * <dt>{@code STOP.EXIT}</dt>
 * <dd>Boolean to indicate if a {@code System.exit(0)} should occur on successful shutdown,
 * defaults to {@code true}</dd>
 * </dl>
 *
 * <p>
 * This starts a {@link java.net.ServerSocket} that listens on the host/port specified by
 * the configuration, and starts a thread to accept incoming requests.
 * </p>
 *
 * <p>
 *     See {@link ShutdownService} for details about commands that can be sent to
 *     this server.
 * </p>
 *
 * @deprecated Replaced with {@link ShutdownService} component, which is not a singleton.
 */
@Deprecated(since = "12.1.0", forRemoval = true)
public class ShutdownMonitor extends ShutdownService
{
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownMonitor.class);
    private static final AtomicReference<ShutdownMonitor> INSTANCE = new AtomicReference<>();

    /**
     * @deprecated No direct replacement, see {@link ShutdownService}, which is not a singleton.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static ShutdownMonitor getInstance()
    {
        return INSTANCE.updateAndGet((h) ->
        {
            if (h != null)
                return h;
            else
            {
                LOG.warn("{} is deprecated, and has been replaced with {}", ShutdownMonitor.class.getName(), ShutdownService.class.getName());
                return new ShutdownMonitor();
            }
        });
    }

    /**
     * Get configured {@code ShutdownMonitor} instance.
     *
     * <ol>
     *   <li>If {@code ShutdownMonitor.getInstance()} has been called,
     *       and it has a valid configuration, return that instance.</li>
     *   <li>If the System Properties exist, and contain a valid
     *       configuration, return a {@code ShutdownMonitor} based on
     *       that configuration. (same instance will be returned by
     *       subsequent calls to {@link #getInstance()}</li>
     * </ol>
     *
     * @return the configured ShutdownMonitor instance, or null if
     *         not configured.
     */
    protected static ShutdownMonitor getConfiguredInstance()
    {
        return INSTANCE.updateAndGet((h) ->
        {
            if (h != null)
                return h;
            if (System.getProperty("STOP.PORT") != null)
            {
                LOG.warn("{} is deprecated, and has been replaced with {}", ShutdownMonitor.class.getName(), ShutdownService.class.getName());
                try
                {
                    return new ShutdownMonitor();
                }
                catch (Throwable x)
                {
                    LOG.warn("Unable to create ShutdownMonitor", x);
                }
            }
            return null;
        });
    }

    /**
     * This existed for test case reasons, it was never a public runtime method.
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    protected static void reset()
    {
        INSTANCE.set(null);
    }

    /**
     * @deprecated See {@link org.eclipse.jetty.server.ShutdownService#addComponent(LifeCycle)}.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static void register(LifeCycle... lifeCycles)
    {
        getInstance().addLifeCycles(lifeCycles);
    }

    /**
     * @deprecated See {@link ShutdownService#removeComponent(LifeCycle)}
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static void deregister(LifeCycle lifeCycle)
    {
        getInstance().removeLifeCycle(lifeCycle);
    }

    /**
     * This existed for test case reasons, it was never a public runtime method.
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public static boolean isRegistered(LifeCycle lifeCycle)
    {
        return getInstance().containsLifeCycle(lifeCycle);
    }

    // A mutable port number, to maintain backward compat with existing ShutdownMonitor API.
    private int mutablePort;
    // A mutable key, to maintain backward compat with existing ShutdownMonitor API.
    private String mutableKey;
    // A mutable exitVm, to maintain backward compat with existing ShutdownMonitor API.
    private boolean mutableExitVm;

    private ShutdownMonitor()
    {
        super(getSysProp("STOP.HOST", "127.0.0.1"),
            Integer.parseInt(getSysProp("STOP.PORT", "0")),
            getSysProp("STOP.KEY", null),
            Boolean.parseBoolean(getSysProp("STOP.EXIT", "true")));

        // Different default port for mutable port
        this.mutablePort = Integer.parseInt(getSysProp("STOP.PORT", "-1"));
        this.mutableKey = super.getKey();
        this.mutableExitVm = super.isExitVm();
    }

    /**
     * Get a System Property with fallback to default value, if the property
     * doesn't exist, or has a blank value. (an empty string is a valid value,
     * which the {@link System#getProperty(String, String)} does not fall back
     * to default when encountering.)
     *
     * @param keyName key name
     * @param defaultValue the value to fall back on if unset or blank.
     * @return the value
     */
    private static String getSysProp(String keyName, String defaultValue)
    {
        String value = System.getProperty(keyName, defaultValue);
        if (StringUtil.isBlank(value))
            return defaultValue;
        else
            return value;
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

    public boolean isConfigured()
    {
        return mutablePort >= 0 && mutablePort <= 0xFFFF;
    }

    /**
     * Does nothing.
     *
     * @deprecated No replacement, use SLF4J Logger at name {@link ShutdownService}
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public void setDebug(boolean flag)
    {
        // does nothing
    }

    /**
     * Does nothing.
     *
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public void setExitVm(boolean exitVm)
    {
        mutableExitVm = exitVm;
    }

    @Override
    public boolean isExitVm()
    {
        return mutableExitVm;
    }

    @Override
    public int getPort()
    {
        return mutablePort;
    }

    /**
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public void setPort(int port)
    {
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException("Invalid port: " + port);
        mutablePort = port;
    }

    /**
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public void setKey(String key)
    {
        mutableKey = key;
    }

    public String getKey()
    {
        return mutableKey;
    }

    @Override
    public void start() throws Exception
    {
        int port = getPort();
        if (port < 0 || port > 0xFFFF)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not enabling ShutdownMonitor, port not configured");
            return;
        }
        super.start();
    }

    @Override
    protected void bound(ServerSocket serverSocket)
    {
        if (serverSocket == null)
            return;
        if (!serverSocket.isBound())
            return;
        mutablePort = serverSocket.getLocalPort();
    }

    /**
     * Does nothing.
     * This existed for test case reasons, it was never a public runtime method.
     *
     * @deprecated No replacement.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
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
    @Deprecated(since = "12.1.0", forRemoval = true)
    protected boolean await(long time, TimeUnit unit) throws InterruptedException
    {
        throw new UnsupportedOperationException("await(long, TimeUnit) no longer supported");
    }

    protected boolean isAlive()
    {
        return isListening();
    }
}
