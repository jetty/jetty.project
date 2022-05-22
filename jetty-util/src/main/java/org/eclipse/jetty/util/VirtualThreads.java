//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VirtualThreads
{
    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreads.class);
    private static final Method startVirtualThread = probeStartVirtualThread();
    private static final Method isVirtualThread = probeIsVirtualThread();

    private static Method probeStartVirtualThread()
    {
        try
        {
            return Thread.class.getMethod("startVirtualThread", Runnable.class);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Virtual thread support is not available in the current Java runtime ({})", System.getProperty("java.version"));
            return null;
        }
    }

    private static Method probeIsVirtualThread()
    {
        try
        {
            return Thread.class.getMethod("isVirtual");
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Virtual thread support is not available in the current Java runtime ({})", System.getProperty("java.version"));
            return null;
        }
    }

    public static boolean areSupported()
    {
        return startVirtualThread != null;
    }

    public static void warn()
    {
        LOG.warn("Virtual thread support is not available (or not enabled via --enable-preview) in the current Java runtime ({})", System.getProperty("java.version"));
    }

    public static boolean startVirtualThread(Runnable task)
    {
        try
        {
            startVirtualThread.invoke(null, task);
            return true;
        }
        catch (Throwable x)
        {
            warn();
            return false;
        }
    }

    public static boolean isVirtualThread()
    {
        try
        {
            return (Boolean)isVirtualThread.invoke(Thread.currentThread());
        }
        catch (Throwable x)
        {
            warn();
            return false;
        }
    }

    private VirtualThreads()
    {
    }
}
