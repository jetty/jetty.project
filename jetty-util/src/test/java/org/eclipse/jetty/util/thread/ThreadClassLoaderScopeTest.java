//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

public class ThreadClassLoaderScopeTest
{
    private static class ClassLoaderFoo extends URLClassLoader
    {
        public ClassLoaderFoo()
        {
            super(new URL[0]);
        }
    }

    private static class ClassLoaderBar extends URLClassLoader
    {
        public ClassLoaderBar()
        {
            super(new URL[0]);
        }
    }

    @Test
    public void testNormal()
    {
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(new ClassLoaderFoo()))
        {
            assertThat("ClassLoader in scope", Thread.currentThread().getContextClassLoader(), instanceOf(ClassLoaderFoo.class));
            assertThat("Scoped ClassLoader", scope.getScopedClassLoader(), instanceOf(ClassLoaderFoo.class));
        }
        assertThat("ClassLoader after scope", Thread.currentThread().getContextClassLoader(), not(instanceOf(ClassLoaderFoo.class)));
    }

    @Test
    public void testWithException()
    {
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(new ClassLoaderBar()))
        {
            assertThat("ClassLoader in 'scope'", Thread.currentThread().getContextClassLoader(), instanceOf(ClassLoaderBar.class));
            assertThat("Scoped ClassLoader", scope.getScopedClassLoader(), instanceOf(ClassLoaderBar.class));
            try (ThreadClassLoaderScope inner = new ThreadClassLoaderScope(new ClassLoaderFoo()))
            {
                assertThat("ClassLoader in 'inner'", Thread.currentThread().getContextClassLoader(), instanceOf(ClassLoaderFoo.class));
                assertThat("Scoped ClassLoader", scope.getScopedClassLoader(), instanceOf(ClassLoaderFoo.class));
                throw new RuntimeException("Intention exception");
            }
        }
        catch (Throwable ignore)
        {
            /* ignore */
        }
        assertThat("ClassLoader after 'scope'", Thread.currentThread().getContextClassLoader(), not(instanceOf(ClassLoaderBar.class)));
    }
}
