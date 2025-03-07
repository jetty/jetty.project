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

    /**
     * Gets all existing environments.
     * @return the environments
     */
    static Collection<Environment> getAll()
    {
        return Collections.unmodifiableCollection(NamedEnvironment.ENVIRONMENTS.values());
    }

    /**
     * Gets the environment with the given name.
     * @param name the environment name
     * @return the environment, or null if no environment with such name exists
     */
    static Environment get(String name)
    {
        return NamedEnvironment.ENVIRONMENTS.get(name);
    }

    /**
     * Gets the environment with the given name, creating it with the default classloader if necessary.
     * @param name the environment name
     * @return the environment
     * @throws IllegalStateException if an environment with the given name but a non-default classloader already exists
     */
    static Environment ensure(String name) throws IllegalStateException
    {
        return NamedEnvironment.ENVIRONMENTS.computeIfAbsent(name, n -> new NamedEnvironment(n, null));
    }

    /**
     * Creates an environment with the given name and classloader.
     * @param name the environment name
     * @param classLoader the environment classloader
     * @return the environment
     * @throws IllegalStateException if an environment with the given name already exists
     */
    static Environment create(String name, ClassLoader classLoader) throws IllegalStateException
    {
        return NamedEnvironment.ENVIRONMENTS.compute(name, (n, environment) ->
        {
            if (environment != null)
                throw new IllegalStateException("Environment already exists: " + n);
            return new NamedEnvironment(n, classLoader);
        });
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
