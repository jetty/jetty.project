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
