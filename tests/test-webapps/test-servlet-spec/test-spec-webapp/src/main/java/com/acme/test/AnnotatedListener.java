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

package com.acme.test;

import javax.annotation.Resource;

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
public class AnnotatedListener implements HttpSessionListener, HttpSessionAttributeListener, HttpSessionActivationListener, ServletContextListener, ServletContextAttributeListener, ServletRequestListener, ServletRequestAttributeListener
{
    @Resource(mappedName = "maxAmount")
    private Double maxAmount;

    @Override
    public void attributeAdded(HttpSessionBindingEvent se)
    {
        // System.err.println("attributedAdded "+se);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeAdded "+scab);
    }

    @Override
    public void attributeAdded(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeAdded "+srae);
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeRemoved "+se);
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeRemoved "+scab);
    }

    @Override
    public void attributeRemoved(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeRemoved "+srae);
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeReplaced "+se);
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeReplaced "+scab);
    }

    @Override
    public void attributeReplaced(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeReplaced "+srae);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        // System.err.println("contextDestroyed "+sce);
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        if (sce.getServletContext().getAttribute("com.acme.AnnotationTest.sclInjectWebListenerTest") != null)
            throw new IllegalStateException("AnnotatedListener already initialized");

        sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclInjectWebListenerTest", Boolean.valueOf(maxAmount != null));

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
        sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclSetSessionTimeout", Boolean.valueOf(setSessionTimeout));

        boolean getSessionTimeout;
        try
        {
            getSessionTimeout = (sce.getServletContext().getSessionTimeout() == 180);
        }
        catch (Exception e)
        {
            getSessionTimeout = false;
        }
        sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclGetSessionTimeout", Boolean.valueOf(getSessionTimeout));
    }

    public void requestCompleted(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre)
    {
        // System.err.println("requestDestroyed "+sre);
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre)
    {
        // System.err.println("requestInitialized "+sre);
    }

    public void requestResumed(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub

    }

    public void requestSuspended(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void sessionCreated(HttpSessionEvent se)
    {
        // System.err.println("sessionCreated "+se);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se)
    {
        // System.err.println("sessionDestroyed "+se);
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        // System.err.println("sessionDidActivate "+se);
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
        // System.err.println("sessionWillPassivate "+se);
    }
}
