//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

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
            assertThat("ClassLoader in scope",Thread.currentThread().getContextClassLoader(),instanceOf(ClassLoaderFoo.class));
            assertThat("Scoped ClassLoader",scope.getScopedClassLoader(),instanceOf(ClassLoaderFoo.class));
        }
        assertThat("ClassLoader after scope",Thread.currentThread().getContextClassLoader(),not(instanceOf(ClassLoaderFoo.class)));
    }

    @Test
    public void testWithException()
    {
        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(new ClassLoaderBar()))
        {
            assertThat("ClassLoader in 'scope'",Thread.currentThread().getContextClassLoader(),instanceOf(ClassLoaderBar.class));
            assertThat("Scoped ClassLoader",scope.getScopedClassLoader(),instanceOf(ClassLoaderBar.class));
            try (ThreadClassLoaderScope inner = new ThreadClassLoaderScope(new ClassLoaderFoo()))
            {
                assertThat("ClassLoader in 'inner'",Thread.currentThread().getContextClassLoader(),instanceOf(ClassLoaderFoo.class));
                assertThat("Scoped ClassLoader",scope.getScopedClassLoader(),instanceOf(ClassLoaderFoo.class));
                throw new RuntimeException("Intention exception");
            }
        }
        catch (Throwable ignore)
        {
            /* ignore */
        }
        assertThat("ClassLoader after 'scope'",Thread.currentThread().getContextClassLoader(),not(instanceOf(ClassLoaderBar.class)));
    }
}
