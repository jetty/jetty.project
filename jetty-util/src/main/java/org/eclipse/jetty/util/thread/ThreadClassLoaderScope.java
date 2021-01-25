//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Closeable;

public class ThreadClassLoaderScope implements Closeable
{
    private final ClassLoader old;
    private final ClassLoader scopedClassLoader;

    public ThreadClassLoaderScope(ClassLoader cl)
    {
        old = Thread.currentThread().getContextClassLoader();
        scopedClassLoader = cl;
        Thread.currentThread().setContextClassLoader(scopedClassLoader);
    }

    @Override
    public void close()
    {
        Thread.currentThread().setContextClassLoader(old);
    }

    public ClassLoader getScopedClassLoader()
    {
        return scopedClassLoader;
    }
}
