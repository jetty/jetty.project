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

/**
 * MemoryUtils provides an abstraction over memory properties and operations.
 */
public class MemoryUtils
{
    private static final int cacheLineBytes;

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
        cacheLineBytes = value;
    }

    private MemoryUtils()
    {
    }

    public static int getCacheLineBytes()
    {
        return cacheLineBytes;
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
        // Use getIntegersPerCacheLine() instead of getLongsPerCacheLine() b/c refs (oops) could be
        // compressed down to 32-bit; maybe this could be detected?
        return getIntegersPerCacheLine();
    }
}
