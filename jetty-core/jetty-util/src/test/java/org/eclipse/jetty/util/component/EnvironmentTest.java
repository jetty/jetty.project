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

import java.net.URL;
import java.net.URLClassLoader;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EnvironmentTest
{
    @Test
    public void testCreate() throws Exception
    {
        ClassLoader loaderA = new URLClassLoader(new URL[0]);
        Environment envA = Environment.create("envA", loaderA);
        assertThat(envA.getClassLoader(), sameInstance(loaderA));

        ClassLoader loaderB = new URLClassLoader(new URL[0]);
        Environment envB = Environment.create("envB", loaderB);
        assertThat(envB.getClassLoader(), sameInstance(loaderB));

        assertThat(Environment.get("envA"), sameInstance(envA));
        assertThat(Environment.get("envB"), sameInstance(envB));

        @SuppressWarnings("resource")
        ClassLoader loaderAX = new URLClassLoader(new URL[0]);
        assertThrows(IllegalStateException.class, () -> Environment.create("envA", loaderAX));

        System.err.println(Environment.getAll());
        assertThat(Environment.getAll(), Matchers.hasItems(envA, envB));
    }

    @Test
    public void testEnsure() throws Exception
    {
        ClassLoader loaderC = new URLClassLoader(new URL[0]);
        @SuppressWarnings("resource")
        ClassLoader loaderD = new URLClassLoader(new URL[0]);
        Environment envC = Environment.create("envC", loaderC);

        assertThat(Environment.ensure("envC", loaderC), sameInstance(envC));
        assertThrows(IllegalArgumentException.class, () -> Environment.ensure("envC", loaderD));

        Environment envD = Environment.ensure("envD", loaderD);
        assertThat(envD.getClassLoader(), sameInstance(loaderD));
        assertThat(Environment.get("envD"), sameInstance(envD));
        assertThat(Environment.getAll(), Matchers.hasItems(envC, envD));
    }
}
