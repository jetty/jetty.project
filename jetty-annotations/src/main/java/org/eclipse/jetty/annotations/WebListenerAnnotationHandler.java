// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.annotations;

import java.util.List;

import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class WebListenerAnnotationHandler implements DiscoverableAnnotationHandler
{

    protected WebAppContext _wac;

    public WebListenerAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        // TODO Auto-generated method stub
        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null, className);

            if (ServletContextListener.class.isAssignableFrom(clazz) || 
                    ServletContextAttributeListener.class.isAssignableFrom(clazz) ||
                    ServletRequestListener.class.isAssignableFrom(clazz) ||
                    ServletRequestAttributeListener.class.isAssignableFrom(clazz) ||
                    HttpSessionListener.class.isAssignableFrom(clazz) ||
                    HttpSessionAttributeListener.class.isAssignableFrom(clazz))
            {
                java.util.EventListener listener = (java.util.EventListener)clazz.newInstance();
                _wac.addEventListener(listener);
            }
            else
                Log.warn(clazz.getName()+" does not implement one of the servlet listener interfaces");
        }
        catch (Exception e)
        {
            Log.warn(e);
            return;
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn ("@WebListener is not applicable to fields: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        Log.warn ("@WebListener is not applicable to methods: "+className+"."+methodName+" "+signature);
    }

}
