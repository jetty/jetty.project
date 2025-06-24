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

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.TypeUtil;

/**
 * A named runtime environment containing a {@link ClassLoader} and {@link Attributes}.
 */
public interface Environment extends Attributes
{
    // Ensure there is a core environment for possible later deployments to it
    Environment CORE = ensure("core");

    static Collection<Environment> getAll()
    {
        return Collections.unmodifiableCollection(Named.__environments.values());
    }
    
    static Environment get(String name)
    {
        return Named.__environments.get(name);
    }

    static Environment ensure(String name)
    {
        return Named.__environments.computeIfAbsent(name, Named::new);
    }

    static Environment set(Environment environment)
    {
        return Named.__environments.put(environment.getName(), environment);
    }

    /**
     * @return The case-insensitive name of the environment.
     */
    String getName();

    /**
     * @return The {@link ClassLoader} for the environment or if non set, then the {@link ClassLoader} that
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

    class Named extends Attributes.Mapped implements Environment, Dumpable
    {
        private static final Map<String, Environment> __environments = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
        private final String _name;
        private final ClassLoader _classLoader;

        public Named(String name)
        {
            this(name, null);
        }

        public Named(String name, ClassLoader classLoader)
        {
            _name = name;
            _classLoader = classLoader == null ? this.getClass().getClassLoader() : classLoader;
        }

        @Override
        public String getName()
        {
            return _name;
        }

        @Override
        public ClassLoader getClassLoader()
        {
            return _classLoader;
        }

        @Override
        public String dump()
        {
            return Dumpable.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent,
                this,
                new ClassLoaderDump(getClassLoader()),
                new DumpableCollection("Attributes " + _name, asAttributeMap().entrySet()));
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(TypeUtil.toShortName(this.getClass()), hashCode(), _name);
        }
    }

}
