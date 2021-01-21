//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Provide for a Uptime class that is compatible with Android, GAE, and the new Java 8 compact profiles
 */
public class Uptime
{
    public static final int NOIMPL = -1;

    public interface Impl
    {
        long getUptime();
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
                Class<?> mgmtFactory = Class.forName("java.lang.management.ManagementFactory", true, cl);
                Class<?> runtimeClass = Class.forName("java.lang.management.RuntimeMXBean", true, cl);
                Class<?>[] noparams = new Class<?>[0];
                Method mxBeanMethod = mgmtFactory.getMethod("getRuntimeMXBean", noparams);
                if (mxBeanMethod == null)
                {
                    throw new UnsupportedOperationException("method getRuntimeMXBean() not found");
                }
                mxBean = mxBeanMethod.invoke(mgmtFactory);
                if (mxBean == null)
                {
                    throw new UnsupportedOperationException("getRuntimeMXBean() method returned null");
                }
                uptimeMethod = runtimeClass.getMethod("getUptime", noparams);
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
