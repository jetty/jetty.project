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

package com.acme;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.HttpConstraintElement;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

public class TestListener implements HttpSessionListener, HttpSessionAttributeListener, HttpSessionActivationListener, ServletContextListener, ServletContextAttributeListener, ServletRequestListener, ServletRequestAttributeListener
{
    Map<String, Throwable> _called = new HashMap<>();

    public TestListener()
    {
        _called.put("TestListener", new Throwable());
    }

    @PostConstruct
    public void postConstruct()
    {
        _called.put("postConstruct", new Throwable());
    }

    @PreDestroy
    public void preDestroy()
    {
        _called.put("preDestroy", new Throwable());
    }

    @Override
    public void attributeAdded(HttpSessionBindingEvent se)
    {
        // System.err.println("attributedAdded "+se);

        _called.put("attributeAdded", new Throwable());
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeRemoved "+se);
        _called.put("attributeRemoved", new Throwable());
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeReplaced "+se);
        _called.put("attributeReplaced", new Throwable());
    }

    @Override
    public void sessionWillPassivate(HttpSessionEvent se)
    {
        // System.err.println("sessionWillPassivate "+se);
        _called.put("sessionWillPassivate", new Throwable());
    }

    @Override
    public void sessionDidActivate(HttpSessionEvent se)
    {
        // System.err.println("sessionDidActivate "+se);
        _called.put("sessionDidActivate", new Throwable());
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        // System.err.println("contextInitialized "+sce);
        _called.put("contextInitialized", new Throwable());

        //configure programmatic security
        ServletRegistration.Dynamic rego = sce.getServletContext().addServlet("RegoTest", RegTest.class.getName());
        rego.addMapping("/rego/*");
        HttpConstraintElement constraintElement = new HttpConstraintElement(ServletSecurity.EmptyRoleSemantic.PERMIT,
            ServletSecurity.TransportGuarantee.NONE, "admin");
        ServletSecurityElement securityElement = new ServletSecurityElement(constraintElement, null);
        Set<String> unchanged = rego.setServletSecurity(securityElement);
        //// System.err.println("Security constraints registered: "+unchanged.isEmpty());

        //Test that a security constraint from web.xml can't be overridden programmatically
        ServletRegistration.Dynamic rego2 = sce.getServletContext().addServlet("RegoTest2", RegTest.class.getName());
        rego2.addMapping("/rego2/*");
        securityElement = new ServletSecurityElement(constraintElement, null);
        unchanged = rego2.setServletSecurity(securityElement);
        //// System.err.println("Overridding web.xml constraints not possible:" +!unchanged.isEmpty());

        /* For servlet 3.0 */
        FilterRegistration registration = sce.getServletContext().addFilter("TestFilter", TestFilter.class.getName());
        if (registration != null) //otherwise defined in web.xml
        {
            ((FilterRegistration.Dynamic)registration).setAsyncSupported(true);
        }
        else
        {
            registration = sce.getServletContext().getFilterRegistration("TestFilter");
        }
        registration.setInitParameter("remote", "false");
        registration.addMappingForUrlPatterns(
            EnumSet.of(DispatcherType.ERROR, DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.REQUEST),
            true,
            "/*");

        try
        {
            AddListServletRequestListener listenerClass =
                sce.getServletContext().createListener(AddListServletRequestListener.class);
            sce.getServletContext().addListener(listenerClass);
        }
        catch (ServletException e)
        {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        _called.put("contextDestroyed", new Throwable());
        // System.err.println("contextDestroyed "+sce);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent scab)
    {
        _called.put("attributeAdded", new Throwable());
        // System.err.println("attributeAdded "+scab);
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent scab)
    {
        _called.put("attributeRemoved", new Throwable());
        // System.err.println("attributeRemoved "+scab);
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent scab)
    {
        _called.put("attributeReplaced", new Throwable());
        // System.err.println("attributeReplaced "+scab);
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre)
    {
        _called.put("requestDestroyed", new Throwable());
        ((HttpServletRequest)sre.getServletRequest()).getSession(false);
        sre.getServletRequest().setAttribute("requestInitialized", null);
        // System.err.println("requestDestroyed "+sre);
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre)
    {
        _called.put("requestInitialized", new Throwable());
        sre.getServletRequest().setAttribute("requestInitialized", "'" + sre.getServletContext().getContextPath() + "'");
        // System.err.println("requestInitialized "+sre);
    }

    @Override
    public void attributeAdded(ServletRequestAttributeEvent srae)
    {
        _called.put("attributeAdded", new Throwable());
        // System.err.println("attributeAdded "+srae);
    }

    @Override
    public void attributeRemoved(ServletRequestAttributeEvent srae)
    {
        _called.put("attributeRemoved", new Throwable());
        // System.err.println("attributeRemoved "+srae);
    }

    @Override
    public void attributeReplaced(ServletRequestAttributeEvent srae)
    {
        _called.put("attributeReplaced", new Throwable());
        // System.err.println("attributeReplaced "+srae);
    }

    @Override
    public void sessionCreated(HttpSessionEvent se)
    {
        _called.put("sessionCreated", new Throwable());
        // System.err.println("sessionCreated "+se);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se)
    {
        _called.put("sessionDestroyed", new Throwable());
        // System.err.println("sessionDestroyed "+se);
    }
}
