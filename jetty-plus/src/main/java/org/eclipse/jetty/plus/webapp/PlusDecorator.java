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

package org.eclipse.jetty.plus.webapp;

import java.util.EventListener;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler.Decorator;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebAppDecorator
 *
 *
 */
public class PlusDecorator implements Decorator
{
    private static final Logger LOG = Log.getLogger(PlusDecorator.class);

    protected WebAppContext _context;

    public PlusDecorator (WebAppContext context)
    {
        _context = context;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateFilterHolder(org.eclipse.jetty.servlet.FilterHolder)
     */
    public void decorateFilterHolder(FilterHolder filter) throws ServletException
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateFilterInstance(javax.servlet.Filter)
     */
    public <T extends Filter> T decorateFilterInstance(T filter) throws ServletException
    {
        decorate(filter);
        return filter;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateListenerInstance(java.util.EventListener)
     */
    public <T extends EventListener> T decorateListenerInstance(T listener) throws ServletException
    {
        decorate(listener);
        return listener;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateServletHolder(org.eclipse.jetty.servlet.ServletHolder)
     */
    public void decorateServletHolder(ServletHolder holder) throws ServletException
    {
        decorate(holder);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateServletInstance(javax.servlet.Servlet)
     */
    public <T extends Servlet> T decorateServletInstance(T servlet) throws ServletException
    {
        decorate(servlet);
        return servlet;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyFilterInstance(javax.servlet.Filter)
     */
    public void destroyFilterInstance(Filter f)
    {
        destroy(f);
    }


    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyServletInstance(javax.servlet.Servlet)
     */
    public void destroyServletInstance(Servlet s)
    {
        destroy(s);
    }

    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyListenerInstance(java.util.EventListener)
     */
    public void destroyListenerInstance(EventListener l)
    {
        destroy(l);
    }


    protected void decorate (Object o) 
    throws ServletException
    {       

        RunAsCollection runAses = (RunAsCollection)_context.getAttribute(RunAsCollection.RUNAS_COLLECTION);
        if (runAses != null)
            runAses.setRunAs(o);
        
        InjectionCollection injections = (InjectionCollection)_context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        if (injections != null)
            injections.inject(o);

        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        if (callbacks != null)
        {
            try
            {
                callbacks.callPostConstructCallback(o);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }
    } 
    
    protected void destroy (Object o)
    {
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION); 
        if (callbacks != null)
        {
            try
            {
                callbacks.callPreDestroyCallback(o);
            }
            catch (Exception e)
            {
                LOG.warn("Destroying instance of "+o.getClass(), e);
            }
        }
    }
}
