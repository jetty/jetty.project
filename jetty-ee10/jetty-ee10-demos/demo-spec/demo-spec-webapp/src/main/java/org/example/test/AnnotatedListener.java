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

package org.example.test;

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
import jakarta.servlet.http.HttpSessionListener;

@WebListener
public class AnnotatedListener implements HttpSessionListener,
    HttpSessionAttributeListener,
    HttpSessionActivationListener,
    ServletContextListener,
    ServletContextAttributeListener,
    ServletRequestListener,
    ServletRequestAttributeListener
{
    private static final Logger LOG = Logger.getLogger(AnnotatedListener.class.getName());

    @Resource(mappedName = "maxAmount")
    private Double maxAmount;

    @Override
    public void attributeAdded(HttpSessionBindingEvent se)
    {
        LOG.fine("attributedAdded " + se);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent scae)
    {
        LOG.fine("attributeAdded " + scae);
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
        if (sce.getServletContext().getAttribute("org.example.AnnotationTest.sclInjectWebListenerTest") != null)
            throw new IllegalStateException("AnnotatedListener already initialized");

        sce.getServletContext().setAttribute("org.example.AnnotationTest.sclInjectWebListenerTest", maxAmount != null);

        boolean setSessionTimeout;
        try
        {
            sce.getServletContext().setSessionTimeout(180);
            setSessionTimeout = true;
        }
        catch (Exception e)
        {
            setSessionTimeout = false;
        }
        sce.getServletContext().setAttribute("org.example.AnnotationTest.sclSetSessionTimeout", setSessionTimeout);

        boolean getSessionTimeout;
        try
        {
            getSessionTimeout = (sce.getServletContext().getSessionTimeout() == 180);
        }
        catch (Exception e)
        {
            getSessionTimeout = false;
        }
        sce.getServletContext().setAttribute("org.example.AnnotationTest.sclGetSessionTimeout", getSessionTimeout);
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

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        LOG.fine("sessionDidActivate " + se);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
        LOG.fine("sessionWillPassivate " + se);
    }
}
