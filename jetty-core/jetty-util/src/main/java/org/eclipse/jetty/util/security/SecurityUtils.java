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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import javax.security.auth.Subject;

/**
 * <p>Collections of utility methods to deal with the scheduled removal
 * of the security classes defined by <a href="https://openjdk.org/jeps/411">JEP 411</a>.</p>
 * <p>To enable usage of a {@link SecurityManager}, the system property {@link #USE_SECURITY_MANAGER} must be set to {@code true}</p>
 */
public class SecurityUtils
{
    public static final boolean USE_SECURITY_MANAGER = Boolean.getBoolean("org.eclipse.jetty.util.security.useSecurityManager");
    private static final MethodHandle doAs = lookupDoAs();
    private static final MethodHandle doPrivileged = lookupDoPrivileged();

    private static MethodHandle lookupDoAs()
    {
        if (!USE_SECURITY_MANAGER)
            return null;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try
        {
            // Subject.doAs() is deprecated for removal and replaced by Subject.callAs().
            // Lookup first the new API, since for Java versions where both exists, the
            // new API delegates to the old API (for example Java 18, 19 and 20).
            // Otherwise (Java 17), lookup the old API.
            return lookup.findStatic(Subject.class, "callAs", MethodType.methodType(Object.class, Subject.class, Callable.class));
        }
        catch (Throwable x)
        {
            try
            {
                // Lookup the old API.
                MethodType oldSignature = MethodType.methodType(Object.class, Subject.class, PrivilegedAction.class);
                MethodHandle doAs = lookup.findStatic(Subject.class, "doAs", oldSignature);
                // Convert the Callable used in the new API to the PrivilegedAction used in the old API.
                MethodType convertSignature = MethodType.methodType(PrivilegedAction.class, Callable.class);
                MethodHandle converter = lookup.findStatic(SecurityUtils.class, "callableToPrivilegedAction", convertSignature);
                return MethodHandles.filterArguments(doAs, 1, converter);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }

    private static MethodHandle lookupDoPrivileged()
    {
        if (!USE_SECURITY_MANAGER)
            return null;
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
     * Get the current security manager, if available.
     * @return the current security manager, if available
     */
    public static Object getSecurityManager()
    {
        try
        {
            if (!USE_SECURITY_MANAGER)
                return null;
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
        if (!USE_SECURITY_MANAGER)
            return;
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
        if (!USE_SECURITY_MANAGER || doPrivileged == null)
            return action.run();
        return doPrivileged(doPrivileged, action);
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

    /**
     * <p>Runs the given action as the given subject.</p>
     *
     * @param subject the subject this action runs as
     * @param action the action to run
     * @return the result of the action
     * @param <T> the type of the result
     */
    @SuppressWarnings("unchecked")
    public static <T> T doAs(Subject subject, Callable<T> action)
    {
        try
        {
            if (!USE_SECURITY_MANAGER || doAs == null)
                return action.call();
            return (T)doAs.invoke(subject, action);
        }
        catch (RuntimeException | Error x)
        {
            throw x;
        }
        catch (Throwable x)
        {
            throw new CompletionException(x);
        }
    }

    private static <T> PrivilegedAction<T> callableToPrivilegedAction(Callable<T> callable)
    {
        return () ->
        {
            try
            {
                return callable.call();
            }
            catch (RuntimeException x)
            {
                throw x;
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        };
    }

    private SecurityUtils()
    {
    }
}
