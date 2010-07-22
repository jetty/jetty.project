// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.webapp;

import java.util.EventListener;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletContextHandler.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebAppDecorator
 *
 *
 */
public class WebAppDecorator implements Decorator
{
    private InjectionCollection _injections;
    private LifeCycleCallbackCollection _callbacks;
    private RunAsCollection _runAses;

    public WebAppDecorator ()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#cloneFor(org.eclipse.jetty.server.handler.ContextHandler)
     */
    public Decorator cloneFor(ContextHandler context)
    {
        // TODO maybe need to check for non-shared classloader???
        return this;
    }



    public InjectionCollection getInjections()
    {
        return _injections;
    }



    public void setInjections(InjectionCollection injections)
    {
        _injections = injections;
    }



    public LifeCycleCallbackCollection getLifecycleCallbacks()
    {
        return _callbacks;
    }



    public void setLifecycleCallbacks(LifeCycleCallbackCollection lifecycleCallbacks)
    {
        _callbacks = lifecycleCallbacks;
    }



    public RunAsCollection getRunAses()
    {
        return _runAses;
    }



    public void setRunAses(RunAsCollection runAses)
    {
        _runAses = runAses;
    }



    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#decorateFilterHolder(org.eclipse.jetty.servlet.FilterHolder)
     */
    public void decorateFilterHolder(FilterHolder filter) throws ServletException
    {
        // TODO Auto-generated method stub
        
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


        if (_runAses != null)
            _runAses.setRunAs(o);

        if (_injections != null)
            _injections.inject(o);

        if (_callbacks != null)
        {
            try
            {
                _callbacks.callPostConstructCallback(o);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }
    } 
    
    protected void destroy (Object o)
    {
        if (_callbacks != null)
        {
            try
            {
                _callbacks.callPreDestroyCallback(o);
            }
            catch (Exception e)
            {
                Log.warn("Destroying instance of "+o.getClass(), e);
            }
        }
    }
}
