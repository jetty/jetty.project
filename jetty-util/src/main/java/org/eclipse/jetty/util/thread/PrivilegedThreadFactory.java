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

package org.eclipse.jetty.util.thread;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.function.Supplier;

/**
 * <p>Convenience {@link Thread} factory that ensure threads are
 * created without referencing any web application {@link ClassLoader}.</p>
 * <p>Up to Java 17, the {@code Thread} constructor was taking a
 * snapshot of the calling context, which may contain a {@link ProtectionDomain}
 * that references a web application {@code ClassLoader}
 * (for example if the creation of the {@code Thread} was triggered
 * by some operation performed by the web application).
 * The {@code Thread} might then be pooled and prevent the
 * web application {@code ClassLoader} to be garbage collected
 * when the web application is undeployed.
 * For this reason, {@code Thread}s must be created in a privileged
 * action, which restricts the calling context to just the caller
 * frame, not all the frames in the stack.</p>
 * <p>Since Java 18 and the removal of the Java security manager
 * and related classes by JEP 411, {@code Thread}s do not retain
 * the calling context, so there is no need to create them in a
 * privileged action.</p>
 */
class PrivilegedThreadFactory
{
    private static final MethodHandle privileged = lookup();

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
     * <p>Creates a new {@link Thread} from the given {@link Supplier},
     * without retaining the calling context.</p>
     *
     * @param supplier the {@link Supplier} that creates the {@link Thread}
     * @return a new {@link Thread} without retaining the calling context
     */
    static <T extends Thread> T newThread(Supplier<T> supplier)
    {
        // Keep this method short and inlineable.
        MethodHandle methodHandle = privileged;
        if (methodHandle == null)
            return supplier.get();
        return privilegedNewThread(methodHandle, supplier);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Thread> T privilegedNewThread(MethodHandle privileged, Supplier<T> supplier)
    {
        try
        {
            PrivilegedAction<T> action = supplier::get;
            return (T)privileged.invoke(action);
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

    private PrivilegedThreadFactory()
    {
    }
}
