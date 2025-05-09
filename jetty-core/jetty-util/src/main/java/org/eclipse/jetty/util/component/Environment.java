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
import java.util.Objects;

import org.eclipse.jetty.util.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A named runtime environment containing a {@link ClassLoader} and {@link Attributes}.
 */
public interface Environment extends Attributes
{
    Logger LOG = LoggerFactory.getLogger(Environment.class);

    /**
     * Gets all existing environments.
     * @return the environments
     */
    static Collection<Environment> getAll()
    {
        return Collections.unmodifiableCollection(NamedEnvironment.ENVIRONMENTS.values());
    }

    /**
     * Removes all the environments.
     */
    static void removeAll()
    {
        NamedEnvironment.ENVIRONMENTS.clear();
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
     *
     * @param name the environment name
     * @param classToLoad A class to either: use to create the environment with its classloader;
     *                    or to check the environments can load the passed class.
     * @return the environment
     * @throws IllegalArgumentException if an environment with the given name but a different classloader already exists
     */
    static Environment ensure(String name, Class<?> classToLoad) throws IllegalStateException
    {
        ClassLoader loader = Objects.requireNonNull(classToLoad).getClassLoader() == null ? Environment.class.getClassLoader() : classToLoad.getClassLoader();
        Environment environment = NamedEnvironment.ENVIRONMENTS.computeIfAbsent(name, n -> new NamedEnvironment(n, loader));
        if (LOG.isDebugEnabled())
            LOG.debug("Environment.ensure: {} {} {}", name, classToLoad, environment);

        if (environment.getClassLoader() == loader)
            return environment;

        if (LOG.isDebugEnabled())
            LOG.debug("Environment.ensure: {} not same loader {} != {}", name, environment.getClassLoader(), loader);

        // TODO This is to work around a JPMS environment bug.
        try
        {
            Class<?> loadClass = environment.getClassLoader().loadClass(classToLoad.getName());
            if (loadClass == classToLoad)
                return environment;
            if (LOG.isDebugEnabled())
                LOG.debug("Environment.ensure: {} cannot load same class instance {} != {}", name, loadClass.getClassLoader(), classToLoad.getClassLoader());
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalArgumentException("%s has different classloader".formatted(name), e);
        }

        throw new IllegalArgumentException("%s has different classloader".formatted(name));
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
            if (LOG.isDebugEnabled())
                LOG.debug("Environment.create: {} {} {}", name, classLoader, environment);
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
