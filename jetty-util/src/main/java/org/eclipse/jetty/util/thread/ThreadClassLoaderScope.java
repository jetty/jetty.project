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
