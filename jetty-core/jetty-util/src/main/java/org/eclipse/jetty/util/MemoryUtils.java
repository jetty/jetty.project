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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MemoryUtils provides an abstraction over memory properties and operations.
 */
public class MemoryUtils
{
    private static final Logger LOG = LoggerFactory.getLogger(MemoryUtils.class);
    private static final int CACHE_LINE_BYTES;
    private static final int REFERENCES_PER_CACHE_LINE;

    static
    {
        int cacheLineBytes = 64;
        try
        {
            cacheLineBytes = Integer.parseInt(System.getProperty("org.eclipse.jetty.util.cacheLineBytes", String.valueOf(cacheLineBytes)));
        }
        catch (Exception e)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("", e);
        }
        CACHE_LINE_BYTES = cacheLineBytes;

        int referencesPerCacheLine = -1;
        try
        {
            String property = System.getProperty("org.eclipse.jetty.util.referencesPerCacheLine");
            if (property != null)
            {
                referencesPerCacheLine = Integer.parseInt(property);
                if (referencesPerCacheLine < 1)
                    referencesPerCacheLine = -1;
            }
        }
        catch (Exception e)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("", e);
        }
        if (referencesPerCacheLine == -1)
        {
            try
            {
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                ObjectName beanName = ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
                Object vmOption = server.invoke(beanName, "getVMOption",
                    new Object[]{"UseCompressedOops"},
                    new String[]{"java.lang.String"});
                String v = (String)((CompositeData)vmOption).get("value");
                if (!Boolean.parseBoolean(v))
                    referencesPerCacheLine = getLongsPerCacheLine();
            }
            catch (Throwable x)
            {
                if (LOG.isTraceEnabled())
                    LOG.trace("", x);
            }
        }
        // Use pessimistic default: assume oops are compressed to 32-bit.
        if (referencesPerCacheLine == -1)
            referencesPerCacheLine = getIntegersPerCacheLine();
        REFERENCES_PER_CACHE_LINE = referencesPerCacheLine;
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
        return REFERENCES_PER_CACHE_LINE;
    }
}
