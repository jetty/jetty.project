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

        /**
         * @see javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
         */
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            throw new IllegalStateException("BAR LISTENER CALLED!");
        }

        /**
         * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
         */
        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {

        }
    }

    public static class FooListener implements ServletContextListener
    {

        /**
         * @see javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
         */
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

        /**
         * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
         */
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
    }
}
