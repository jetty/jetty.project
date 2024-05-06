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

package org.example.test;

import java.util.EventListener;
import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

@org.example.initializer.Foo(1)
@WebListener
public class TestListener implements HttpSessionListener,
    HttpSessionAttributeListener,
    HttpSessionActivationListener,
    ServletContextListener,
    ServletContextAttributeListener,
    ServletRequestListener,
    ServletRequestAttributeListener
{
    private static final Logger LOG = Logger.getLogger(TestListener.class.getName());
    @Resource(mappedName = "maxAmount")
    private Double maxAmount;

    @Override
    public void attributeAdded(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeAdded " + se);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeAdded " + scab);
    }

    @Override
    public void attributeAdded(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeAdded " + srae);
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeRemoved " + se);
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeRemoved " + scab);
    }

    @Override
    public void attributeRemoved(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeRemoved " + srae);
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeReplaced " + se);
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeReplaced " + scab);
    }

    @Override
    public void attributeReplaced(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeReplaced " + srae);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        LOG.fine("contextDestroyed " + sce);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        if (sce.getServletContext().getAttribute("org.example.AnnotationTest.sclInjectTest") != null)
            throw new IllegalStateException("TestListener already initialized");

        sce.getServletContext().setAttribute("org.example.AnnotationTest.sclInjectTest", maxAmount != null);

        // Can't add a ServletContextListener from a ServletContextListener even if it is declared in web.xml
        try
        {
            sce.getServletContext().addListener(new NaughtyServletContextListener());
            sce.getServletContext().setAttribute("org.example.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("org.example.AnnotationTest.sclFromSclRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("org.example.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }

        // Can't add an EventListener not part of the specified list for addListener()
        try
        {
            sce.getServletContext().addListener(new InvalidListener());
            sce.getServletContext().setAttribute("org.example.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("org.example.AnnotationTest.invalidListenerRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("org.example.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        }

        // Programmatically add a listener and make sure its injected
        try
        {
            ValidListener l = sce.getServletContext().createListener(ValidListener.class);
            sce.getServletContext().setAttribute("org.example.AnnotationTest.programListenerInjectTest", l != null && l.maxAmount != null);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("org.example.AnnotationTest.programListenerInjectTest", Boolean.FALSE);
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre)
    {
        LOG.fine("requestDestroyed " + sre);
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre)
    {
        LOG.fine("requestInitialized " + sre);
    }

    @Override
    public void sessionCreated(HttpSessionEvent se)
    {
        LOG.fine("sessionCreated " + se);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se)
    {
        LOG.fine("sessionDestroyed " + se);
    }

    public static class NaughtyServletContextListener implements ServletContextListener
    {

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextDestroyed");
        }

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextInitialized");
        }
    }

    public static class InvalidListener implements EventListener
    {
        public InvalidListener()
        {
        }
    }

    public static class ValidListener implements HttpSessionIdListener
    {
        @Resource(mappedName = "maxAmount")
        private Double maxAmount;

        public ValidListener()
        {
        }

        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
        }
    }
}
