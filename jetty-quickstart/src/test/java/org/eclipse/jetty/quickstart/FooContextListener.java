//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quickstart;

import java.util.Collection;
import java.util.Set;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FooContextListener
 */
public class FooContextListener implements ServletContextListener
{
    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        ServletRegistration defaultRego = sce.getServletContext().getServletRegistration("default");
        Collection<String> mappings = defaultRego.getMappings();
        assertThat("/", is(in(mappings)));

        Set<String> otherMappings = sce.getServletContext().getServletRegistration("foo").addMapping("/");
        assertTrue(otherMappings.isEmpty());
        Collection<String> fooMappings = sce.getServletContext().getServletRegistration("foo").getMappings();
        assertThat("/", is(in(fooMappings)));
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
    }
}
