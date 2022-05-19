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

package org.eclipse.jetty.util.component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.Attributes;

public interface Environment extends Attributes
{
    Map<String, Environment> __environments = new ConcurrentHashMap<>();

    static Environment get(String name)
    {
        return __environments.get(name);
    }

    static Environment set(Environment environment)
    {
        return __environments.put(environment.getName(), environment);
    }

    String getName();

    ClassLoader getClassLoader();

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
