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

package org.example.initializer;

import java.util.ArrayList;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;

@HandlesTypes({jakarta.servlet.Servlet.class, Foo.class})
public class FooInitializer implements ServletContainerInitializer
{
    public static class BarListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            throw new IllegalStateException("BAR LISTENER CALLED!");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {

        }
    }

    public static class FooListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            if (sce.getServletContext().getAttribute("org.example.AnnotationTest.listenerTest") != null)
                throw new IllegalStateException("FooListener already initialized");

            //Can add a ServletContextListener from a ServletContainerInitializer
            sce.getServletContext().setAttribute("org.example.AnnotationTest.listenerTest", Boolean.TRUE);

            //Can't add a ServletContextListener from a ServletContextListener
            try
            {
                sce.getServletContext().addListener(new BarListener());
                sce.getServletContext().setAttribute("org.example.AnnotationTest.listenerRegoTest", Boolean.FALSE);
            }
            catch (UnsupportedOperationException e)
            {
                sce.getServletContext().setAttribute("org.example.AnnotationTest.listenerRegoTest", Boolean.TRUE);
            }
            catch (Exception e)
            {
                sce.getServletContext().setAttribute("org.example.AnnotationTest.listenerRegoTest", Boolean.FALSE);
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {

        }
    }

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context)
    {
        if (context.getAttribute("org.example.Foo") != null)
            throw new IllegalStateException("FooInitializer on Startup already called");

        context.setAttribute("org.example.Foo", new ArrayList<Class>(classes));
        ServletRegistration.Dynamic reg = context.addServlet("AnnotationTest", "org.example.AnnotationTest");
        context.setAttribute("org.example.AnnotationTest.complete", (reg == null));
        context.addListener(new FooListener());

        //test adding jsp file dynamically
        ServletRegistration.Dynamic jspFile = context.addJspFile("dynamic.jsp", "/dynamic.jsp");
        context.setAttribute("org.example.jsp.file", (jspFile != null));
        jspFile.addMapping("/dynamicjsp/*");
    }
}
