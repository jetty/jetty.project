//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.util.EventListener;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletContextHandler.Decorator;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebAppDecoratorWrapper
 *
 *
 */
public class AnnotationDecorator implements Decorator
{
    AnnotationIntrospector _introspector = new AnnotationIntrospector();
    
    /**
     * @param context
     */
    public AnnotationDecorator(WebAppContext context)
    {
        _introspector.registerHandler(new ResourceAnnotationHandler(context));
        _introspector.registerHandler(new ResourcesAnnotationHandler(context));
        _introspector.registerHandler(new RunAsAnnotationHandler(context));
        _introspector.registerHandler(new PostConstructAnnotationHandler(context));
        _introspector.registerHandler(new PreDestroyAnnotationHandler(context));
        _introspector.registerHandler(new DeclareRolesAnnotationHandler(context));
        _introspector.registerHandler(new MultiPartConfigAnnotationHandler(context));
        _introspector.registerHandler(new ServletSecurityAnnotationHandler(context));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filter
     * @throws ServletException
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateFilterHolder(org.eclipse.jetty.servlet.FilterHolder)
     */
    public void decorateFilterHolder(FilterHolder filter) throws ServletException
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param <T>
     * @param filter
     * @return the decorated filter
     * @throws ServletException
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateFilterInstance(javax.servlet.Filter)
     */
    public <T extends Filter> T decorateFilterInstance(T filter) throws ServletException
    {
        introspect(filter);
        return filter;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param <T>
     * @param listener
     * @return the decorated event listener instance
     * @throws ServletException
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateListenerInstance(java.util.EventListener)
     */
    public <T extends EventListener> T decorateListenerInstance(T listener) throws ServletException
    {
        introspect(listener);
        return listener;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servlet
     * @throws ServletException
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateServletHolder(org.eclipse.jetty.servlet.ServletHolder)
     */
    public void decorateServletHolder(ServletHolder servlet) throws ServletException
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @param <T>
     * @param servlet
     * @return the decorated servlet instance
     * @throws ServletException
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateServletInstance(javax.servlet.Servlet)
     */
    public <T extends Servlet> T decorateServletInstance(T servlet) throws ServletException
    {
        introspect(servlet);
        return servlet;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param f
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyFilterInstance(javax.servlet.Filter)
     */
    public void destroyFilterInstance(Filter f)
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @param s
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyServletInstance(javax.servlet.Servlet)
     */
    public void destroyServletInstance(Servlet s)
    {
    }

    
    

  
    /* ------------------------------------------------------------ */
    /**
     * @param f
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyListenerInstance(java.util.EventListener)
     */
    public void destroyListenerInstance(EventListener f)
    {
    }

    /**
     * Look for annotations that can be discovered with introspection:
     * <ul>
     * <li> Resource
     * <li> Resources
     * <li> PostConstruct
     * <li> PreDestroy
     * <li> ServletSecurity?
     * </ul>
     * @param o
     */
    protected void introspect (Object o)
    {
        _introspector.introspect(o.getClass());
    }
}
