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

package com.acme.test;

import java.util.EventListener;
import java.util.logging.Logger;
import javax.annotation.Resource;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

@com.acme.initializer.Foo(1)
@WebListener
public class TestListener implements HttpSessionListener, HttpSessionAttributeListener, HttpSessionActivationListener, ServletContextListener, ServletContextAttributeListener, ServletRequestListener, ServletRequestAttributeListener
{
    private static final Logger LOG = Logger.getLogger(TestListener.class.getName());

    public static class NaughtyServletContextListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextInitialized");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextDestroyed");
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

    @Resource(mappedName = "maxAmount")
    private Double maxAmount;

    @Override
    public void attributeAdded(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeAdded " + se);
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeRemoved " + se);
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent se)
    {
        LOG.fine("attributeReplaced " + se);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
        LOG.fine("sessionWillPassivate " + se);
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        LOG.fine("sessionDidActivate " + se);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        if (sce.getServletContext().getAttribute("com.acme.AnnotationTest.sclInjectTest") != null)
            throw new IllegalStateException("TestListener already initialized");

        sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclInjectTest", Boolean.valueOf(maxAmount != null));

        //Can't add a ServletContextListener from a ServletContextListener even if it is declared in web.xml
        try
        {
            sce.getServletContext().addListener(new NaughtyServletContextListener());
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }

        //Can't add an EventListener not part of the specified list for addListener()
        try
        {
            sce.getServletContext().addListener(new InvalidListener());
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        }

        //Programmatically add a listener and make sure its injected
        try
        {
            ValidListener l = sce.getServletContext().createListener(ValidListener.class);
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.programListenerInjectTest", Boolean.valueOf(l != null && l.maxAmount != null));
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.programListenerInjectTest", Boolean.FALSE);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        LOG.fine("contextDestroyed " + sce);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeAdded " + scab);
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeRemoved " + scab);
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent scab)
    {
        LOG.fine("attributeReplaced " + scab);
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
    public void attributeAdded(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeAdded " + srae);
    }

    @Override
    public void attributeRemoved(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeRemoved " + srae);
    }

    @Override
    public void attributeReplaced(ServletRequestAttributeEvent srae)
    {
        LOG.fine("attributeReplaced " + srae);
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
}
