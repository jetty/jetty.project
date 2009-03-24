// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Method;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class PojoContextListener implements ServletContextListener, PojoWrapper
{
    private Object _pojo;
    private Method _contextDestroyedMethod;
    private Method _contextInitializedMethod;
    private static final Class[] __params = new Class[]{ServletContextEvent.class};
    
    public PojoContextListener(Object pojo)
    throws IllegalArgumentException
    {
        if (pojo==null)
            throw new IllegalArgumentException("Pojo is null");

        _pojo = pojo;
        try
        {
            _contextDestroyedMethod = _pojo.getClass().getDeclaredMethod("contextDestroyed", __params);
            _contextInitializedMethod = _pojo.getClass().getDeclaredMethod("contextInitialized", __params);
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException (e.getLocalizedMessage());   
        }
    }
    
    public Object getPojo()
    {
        return _pojo;
    }

    public void contextDestroyed(ServletContextEvent event)
    {
        try
        {
            _contextDestroyedMethod.invoke(_pojo, new Object[]{event});
        }
        catch (Exception e)
        {
            event.getServletContext().log("Error invoking contextInitialized", e);
        }

    }

    public void contextInitialized(ServletContextEvent event)
    {
        try
        {
            _contextInitializedMethod.invoke(_pojo, new Object[]{event});
        }
        catch (Exception e)
        {
            event.getServletContext().log("Error invoking contextInitialized", e);
        }
    }

}
