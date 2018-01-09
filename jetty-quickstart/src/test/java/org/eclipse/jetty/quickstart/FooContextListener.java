//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.Set;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;

/**
 * FooContextListener
 *
 *
 */
public class FooContextListener implements ServletContextListener
{
    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        ServletRegistration defaultRego = sce.getServletContext().getServletRegistration("default");
        Collection<String> mappings = defaultRego.getMappings();
        assertTrue(mappings.contains("/"));
        
        Set<String> otherMappings = sce.getServletContext().getServletRegistration("foo").addMapping("/");
        assertTrue(otherMappings.isEmpty());
        Collection<String> fooMappings = sce.getServletContext().getServletRegistration("foo").getMappings();
        assertTrue(fooMappings.contains("/"));
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        // TODO Auto-generated method stub
        
    }
}