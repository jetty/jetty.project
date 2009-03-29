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

package org.eclipse.jetty.plus.servlet;

import javax.servlet.Filter;
import javax.servlet.Servlet;

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;

/**
 * ServletHandler
 *
 *
 */
public class ServletHandler extends org.eclipse.jetty.servlet.ServletHandler
{

    private InjectionCollection _injections = null;
    private LifeCycleCallbackCollection _callbacks = null;
    


    /**
     * @return the callbacks
     */
    public LifeCycleCallbackCollection getCallbacks()
    {
        return _callbacks;
    }



    /**
     * @param callbacks the callbacks to set
     */
    public void setCallbacks(LifeCycleCallbackCollection callbacks)
    {
        this._callbacks = callbacks;
    }



    /**
     * @return the injections
     */
    public InjectionCollection getInjections()
    {
        return _injections;
    }



    /**
     * @param injections the injections to set
     */
    public void setInjections(InjectionCollection injections)
    {
        this._injections = injections;
    }
    
    /** 
     * @see org.eclipse.jetty.servlet.ServletHandler#customizeFilter(javax.servlet.Filter)
     */
    public Filter customizeFilter(Filter filter) throws Exception
    {
        if (_injections != null)
            _injections.inject(filter);
        
        if (_callbacks != null)
            _callbacks.callPostConstructCallback(filter);
        return super.customizeFilter(filter); 
    }
    
    

    /** 
     * @see org.eclipse.jetty.servlet.ServletHandler#customizeServlet(javax.servlet.Servlet)
     */
    public Servlet customizeServlet(Servlet servlet) throws Exception
    {      
        if (_injections != null)
            _injections.inject(servlet);
        if (_callbacks != null)
            _callbacks.callPostConstructCallback(servlet);
        return super.customizeServlet(servlet);
    }



    /** 
     * @see org.eclipse.jetty.servlet.servlet.ServletHandler#cusomizeFilterDestroy(javax.servlet.Filter)
     */
    public Filter customizeFilterDestroy(Filter filter) throws Exception
    {
        if (_callbacks != null)
            _callbacks.callPreDestroyCallback(filter);
        return super.customizeFilterDestroy(filter);
    }



    /** 
     * @see org.eclipse.jetty.servlet.servlet.ServletHandler#customizeServletDestroy(javax.servlet.Servlet)
     */
    public Servlet customizeServletDestroy(Servlet servlet) throws Exception
    {
        if (_callbacks != null)
            _callbacks.callPreDestroyCallback(servlet);
        return super.customizeServletDestroy(servlet);
    }
}
