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

package org.eclipse.jetty.util.security;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.Permission;
import java.security.PrivilegedAction;

/**
 * <p>Collections of utility methods to deal with the scheduled removal
 * of the security classes defined by <a href="https://openjdk.org/jeps/411">JEP 411</a>.</p>
 */
public class SecurityUtils
{
    private static final MethodHandle doPrivileged = lookup();

    private static MethodHandle lookup()
    {
        try
        {
            // Use reflection to work with Java versions that have and don't have AccessController.
            Class<?> klass = ClassLoader.getPlatformClassLoader().loadClass("java.security.AccessController");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return lookup.findStatic(klass, "doPrivileged", MethodType.methodType(Object.class, PrivilegedAction.class));
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    /**
     * @return the current security manager, if available
     */
    public static Object getSecurityManager()
    {
        try
        {
            // Use reflection to work with Java versions that have and don't have SecurityManager.
            return System.class.getMethod("getSecurityManager").invoke(null);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * <p>Checks the given permission, if the {@link #getSecurityManager() security manager}
     * is set.</p>
     *
     * @param permission the permission to check
     * @throws SecurityException if the permission check fails
     */
    public static void checkPermission(Permission permission) throws SecurityException
    {
        Object securityManager = SecurityUtils.getSecurityManager();
        if (securityManager == null)
            return;
        try
        {
            securityManager.getClass().getMethod("checkPermission")
                .invoke(securityManager, permission);
        }
        catch (SecurityException x)
        {
            throw x;
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * <p>Runs the given action with the calling context restricted
     * to just the calling frame, not all the frames in the stack.</p>
     *
     * @param action the action to run
     * @return the result of running the action
     * @param <T> the type of the result
     */
    public static <T> T doPrivileged(PrivilegedAction<T> action)
    {
        // Keep this method short and inlineable.
        MethodHandle methodHandle = doPrivileged;
        if (methodHandle == null)
            return action.run();
        return doPrivileged(methodHandle, action);
    }

    @SuppressWarnings("unchecked")
    private static <T> T doPrivileged(MethodHandle doPrivileged, PrivilegedAction<T> action)
    {
        try
        {
            return (T)doPrivileged.invoke(action);
        }
        catch (RuntimeException | Error x)
        {
            throw x;
        }
        catch (Throwable x)
        {
            throw new RuntimeException(x);
        }
    }

    private SecurityUtils()
    {
    }
}
