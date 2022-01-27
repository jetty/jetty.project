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

package com.acme.initializer;

import java.util.ArrayList;
import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;
import javax.servlet.annotation.HandlesTypes;

@HandlesTypes({javax.servlet.Servlet.class, Foo.class})
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
            if (sce.getServletContext().getAttribute("com.acme.AnnotationTest.listenerTest") != null)
                throw new IllegalStateException("FooListener already initialized");

            //Can add a ServletContextListener from a ServletContainerInitializer
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.listenerTest", Boolean.TRUE);

            //Can't add a ServletContextListener from a ServletContextListener
            try
            {
                sce.getServletContext().addListener(new BarListener());
                sce.getServletContext().setAttribute("com.acme.AnnotationTest.listenerRegoTest", Boolean.FALSE);
            }
            catch (UnsupportedOperationException e)
            {
                sce.getServletContext().setAttribute("com.acme.AnnotationTest.listenerRegoTest", Boolean.TRUE);
            }
            catch (Exception e)
            {
                sce.getServletContext().setAttribute("com.acme.AnnotationTest.listenerRegoTest", Boolean.FALSE);
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
        if (context.getAttribute("com.acme.Foo") != null)
            throw new IllegalStateException("FooInitializer on Startup already called");

        context.setAttribute("com.acme.Foo", new ArrayList<Class>(classes));
        ServletRegistration.Dynamic reg = context.addServlet("AnnotationTest", "com.acme.AnnotationTest");
        context.setAttribute("com.acme.AnnotationTest.complete", (reg == null));
        context.addListener(new FooListener());

        //test adding jsp file dynamically
        ServletRegistration.Dynamic jspFile = context.addJspFile("dynamic.jsp", "/dynamic.jsp");
        context.setAttribute("com.acme.jsp.file", (jspFile != null));
        jspFile.addMapping("/dynamicjsp/*");
    }
}
