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

package org.eclipse.jetty.util;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/**
 * MemoryUtils provides an abstraction over memory properties and operations.
 */
public class MemoryUtils
{
    private static final int CACHE_LINE_BYTES;
    private final static int REFERENCE_PER_CACHE_LINE;

    static
    {
        int defaultValue = 64;
        int value = defaultValue;
        try
        {
            value = Integer.parseInt(System.getProperty("org.eclipse.jetty.util.cacheLineBytes", String.valueOf(defaultValue)));
        }
        catch (Exception ignored)
        {
        }
        CACHE_LINE_BYTES = value;

        int referencePerCacheLine = getIntegersPerCacheLine();
        try
        {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName beanName = ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
            Object vmOption = server.invoke(beanName, "getVMOption",
                new Object[]{"UseCompressedOops"},
                new String[]{"java.lang.String"});
            String v = (String)((CompositeData)vmOption).get("value");
            if (!Boolean.parseBoolean(v))
                referencePerCacheLine = getLongsPerCacheLine();
        }
        catch (Throwable ignored)
        {
        }
        REFERENCE_PER_CACHE_LINE = referencePerCacheLine;
    }

    private MemoryUtils()
    {
    }

    public static int getCacheLineBytes()
    {
        return CACHE_LINE_BYTES;
    }

    public static int getIntegersPerCacheLine()
    {
        return getCacheLineBytes() >> 2;
    }

    public static int getLongsPerCacheLine()
    {
        return getCacheLineBytes() >> 3;
    }

    public static int getReferencesPerCacheLine()
    {
        return REFERENCE_PER_CACHE_LINE;
    }

    public static void main(String[] args)
    {
        System.out.println(getReferencesPerCacheLine());
    }
}
