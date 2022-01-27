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

package org.eclipse.jetty.quickstart;

import java.util.Collection;
import java.util.Set;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FooContextListener
 */
public class FooContextListener implements ServletContextListener
{
    static int ___initialized;
    static int __destroyed;

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        ++___initialized;

        ServletRegistration defaultRego = sce.getServletContext().getServletRegistration("default");
        Collection<String> mappings = defaultRego.getMappings();
        assertThat("/", is(in(mappings)));

        ServletRegistration rego = sce.getServletContext().getServletRegistration("foo");
        if (rego != null)
        {
            Set<String> otherMappings = rego.addMapping("/");
            assertTrue(otherMappings.isEmpty());
            Collection<String> fooMappings = rego.getMappings();
            assertThat("/", is(in(fooMappings)));
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        ++__destroyed;
    }
}
