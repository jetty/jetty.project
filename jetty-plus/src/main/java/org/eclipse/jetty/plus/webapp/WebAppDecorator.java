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
import org.eclipse.jetty.servlet.Holder;
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
    private WebAppContext _wac;

    public WebAppDecorator (WebAppContext context)
    {
        _wac = context;
    }
    
    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#filterCreated(javax.servlet.Filter)
     */
    public <T extends Filter> T filterCreated(T filter) throws ServletException
    {
        initialize(filter);
        return filter;
    }


    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#listenerCreated(java.util.EventListener)
     */
    public <T extends EventListener> T listenerCreated(T listener) throws ServletException
    {
        initialize(listener);
        return listener;
    }


    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#servletCreated(javax.servlet.Servlet)
     */
    public <T extends Servlet> T servletCreated(T servlet) throws ServletException
    {
        initialize(servlet);
        return servlet;
    }

    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyFilter(javax.servlet.Filter)
     */
    public void destroyFilter(Filter f)
    {
        destroy(f);
    }


    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyListener(java.util.EventListener)
     */
    public void destroyListener(EventListener l)
    {
        destroy(l);
    }


    /** 
     * @see org.eclipse.jetty.servlet.ServletContextHandler.Decorator#destroyServlet(javax.servlet.Servlet)
     */
    public void destroyServlet(Servlet s)
    {
       destroy(s);
    }


    protected void initialize (Object o) 
    throws ServletException
    {       
        InjectionCollection injections = (InjectionCollection)_wac.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_wac.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        RunAsCollection runAses = (RunAsCollection)_wac.getAttribute(RunAsCollection.RUNAS_COLLECTION);  

        if (runAses != null)
            runAses.setRunAs(o);

        if (injections != null)
            injections.inject(o);

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
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_wac.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        if (callbacks != null)
        {
            try
            {
                callbacks.callPreDestroyCallback(o);
            }
            catch (Exception e)
            {
                Log.warn("Destroying instance of "+o.getClass(), e);
            }
        }
    }
}
