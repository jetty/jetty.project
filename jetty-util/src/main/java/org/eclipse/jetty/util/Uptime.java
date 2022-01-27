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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Provide for a Uptime class that is compatible with Android, GAE, and the new Java 8 compact profiles
 */
public class Uptime
{
    public static final int NOIMPL = -1;

    public static interface Impl
    {
        public long getUptime();
    }

    public static class DefaultImpl implements Impl
    {
        public Object mxBean;
        public Method uptimeMethod;

        public DefaultImpl()
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try
            {
                final Class<?> mgmtFactory = Class.forName("java.lang.management.ManagementFactory", true, cl);
                final Class<?> runtimeClass = Class.forName("java.lang.management.RuntimeMXBean", true, cl);
                final Class<?>[] noParams = new Class<?>[0];
                final Method mxBeanMethod = mgmtFactory.getMethod("getRuntimeMXBean", noParams);
                if (mxBeanMethod == null)
                {
                    throw new UnsupportedOperationException("method getRuntimeMXBean() not found");
                }
                mxBean = mxBeanMethod.invoke(mgmtFactory);
                if (mxBean == null)
                {
                    throw new UnsupportedOperationException("getRuntimeMXBean() method returned null");
                }
                uptimeMethod = runtimeClass.getMethod("getUptime", noParams);
                if (mxBean == null)
                {
                    throw new UnsupportedOperationException("method getUptime() not found");
                }
            }
            catch (ClassNotFoundException |
                NoClassDefFoundError |
                NoSuchMethodException |
                SecurityException |
                IllegalAccessException |
                IllegalArgumentException |
                InvocationTargetException e)
            {
                throw new UnsupportedOperationException("Implementation not available in this environment", e);
            }
        }

        @Override
        public long getUptime()
        {
            try
            {
                return (long)uptimeMethod.invoke(mxBean);
            }
            catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
            {
                return NOIMPL;
            }
        }
    }

    private static final Uptime INSTANCE = new Uptime();

    public static Uptime getInstance()
    {
        return INSTANCE;
    }

    private Impl impl;

    private Uptime()
    {
        try
        {
            impl = new DefaultImpl();
        }
        catch (UnsupportedOperationException e)
        {
            System.err.printf("Defaulting Uptime to NOIMPL due to (%s) %s%n", e.getClass().getName(), e.getMessage());
            impl = null;
        }
    }

    public Impl getImpl()
    {
        return impl;
    }

    public void setImpl(Impl impl)
    {
        this.impl = impl;
    }

    public static long getUptime()
    {
        Uptime u = getInstance();
        if (u == null || u.impl == null)
        {
            return NOIMPL;
        }
        return u.impl.getUptime();
    }
}
