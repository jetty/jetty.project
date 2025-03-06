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

package org.eclipse.jetty.util.component;

import java.util.Collection;
import java.util.Collections;

import org.eclipse.jetty.util.Attributes;

/**
 * A named runtime environment containing a {@link ClassLoader} and {@link Attributes}.
 */
public interface Environment extends Attributes
{
    // Ensure there is a core environment for possible later deployments to it
    Environment CORE = ensure("core");

    static Collection<Environment> getAll()
    {
        return Collections.unmodifiableCollection(NamedEnvironment.__environments.values());
    }
    
    static Environment get(String name)
    {
        return NamedEnvironment.__environments.get(name);
    }

    static Environment ensure(String name)
    {
        return ensure(name, null);
    }

    static Environment ensure(String name, ClassLoader classLoader)
    {
        return NamedEnvironment.__environments.computeIfAbsent(name, n -> new NamedEnvironment(n, classLoader));
    }

    /**
     * @return The case-insensitive name of the environment.
     */
    String getName();

    /**
     * @return The {@link ClassLoader} for the environment or if none set, then the {@link ClassLoader} that
     * loaded the environment implementation.
     */
    ClassLoader getClassLoader();

    /**
     * Run a {@link Runnable} in the environment, i.e. with current {@link Thread#getContextClassLoader()} set to
     * {@link #getClassLoader()}.
     * @param runnable The {@link Runnable} to run in the environment.
     */
    default void run(Runnable runnable)
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClassLoader());
        try
        {
            runnable.run();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}
