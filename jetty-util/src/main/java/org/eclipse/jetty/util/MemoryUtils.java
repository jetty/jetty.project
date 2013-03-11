//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

import sun.misc.Unsafe;

/**
 * {@link MemoryUtils} provides an abstraction over memory properties and operations.
 * <p />
 */
public class MemoryUtils
{
    private static final int cacheLineBytes;
    static
    {
        final int defaultValue = 64;
        int value = defaultValue;
        try
        {
            value = Integer.parseInt(AccessController.doPrivileged(new PrivilegedAction<String>()
            {
                @Override
                public String run()
                {
                    return System.getProperty("org.eclipse.jetty.util.cacheLineBytes", String.valueOf(defaultValue));
                }
            }));
        }
        catch (Exception ignored)
        {
        }
        cacheLineBytes = value;
    }

    private static final Unsafe unsafe;
    static
    {
        try
        {
            unsafe = AccessController.doPrivileged(new PrivilegedExceptionAction<Unsafe>()
            {
                @Override
                public Unsafe run() throws Exception
                {
                    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                    unsafeField.setAccessible(true);
                    return (Unsafe)unsafeField.get(null);
                }
            });
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
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

    public static long arrayElementOffset(Class<?> arrayClass, int elementOffset)
    {
        long base = unsafe.arrayBaseOffset(arrayClass);
        long scale = unsafe.arrayIndexScale(arrayClass);
        return base + scale * elementOffset;
    }

    public static int volatileGetInt(Object array, long offset)
    {
        return unsafe.getIntVolatile(array, offset);
    }

    public static long volatileGetLong(Object array, long offset)
    {
        return unsafe.getLongVolatile(array, offset);
    }

    public static int getAndIncrementInt(Object array, long offset)
    {
        while (true)
        {
            int current = volatileGetInt(array, offset);
            int next = current + 1;
            if (compareAndSetInt(array, offset, current, next))
                return current;
        }
    }

    public static long getAndIncrementLong(Object array, long offset)
    {
        while (true)
        {
            long current = volatileGetLong(array, offset);
            long next = current + 1;
            if (compareAndSetLong(array, offset, current, next))
                return current;
        }
    }

    public static int incrementAndGetInt(Object array, long offset)
    {
        while (true)
        {
            int current = volatileGetInt(array, offset);
            int next = current + 1;
            if (compareAndSetInt(array, offset, current, next))
                return next;
        }
    }

    public static long incrementAndGetLong(Object array, long offset)
    {
        while (true)
        {
            long current = volatileGetLong(array, offset);
            long next = current + 1;
            if (compareAndSetLong(array, offset, current, next))
                return next;
        }
    }

    public static <R> R volatileGetObject(Object array, long offset)
    {
        return (R)unsafe.getObjectVolatile(array, offset);
    }

    public static void volatilePutObject(Object array, long offset, Object element)
    {
        unsafe.putOrderedObject(array, offset, element);
    }

    public static boolean compareAndSetObject(Object array, long offset, Object expected, Object value)
    {
        return unsafe.compareAndSwapObject(array, offset, expected, value);
    }

    public static boolean compareAndSetInt(Object array, long offset, int expected, int value)
    {
        return unsafe.compareAndSwapInt(array, offset, expected, value);
    }

    public static boolean compareAndSetLong(Object array, long offset, long expected, long value)
    {
        return unsafe.compareAndSwapLong(array, offset, expected, value);
    }
}
