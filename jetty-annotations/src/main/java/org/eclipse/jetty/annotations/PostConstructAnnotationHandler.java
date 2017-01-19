//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.annotation.PostConstruct;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PostConstructCallback;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;

public class PostConstructAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    protected WebAppContext _context;

    public PostConstructAnnotationHandler (WebAppContext wac)
    {
        super(true);
        _context = wac;
    }


    public void doHandle(Class clazz)
    {
        //Check that the PostConstruct is on a class that we're interested in
        if (supportsPostConstruct(clazz))
        {
            Method[] methods = clazz.getDeclaredMethods();
            for (int i=0; i<methods.length; i++)
            {
                Method m = (Method)methods[i];
                if (m.isAnnotationPresent(PostConstruct.class))
                {
                    if (m.getParameterCount() != 0)
                        throw new IllegalStateException(m+" has parameters");
                    if (m.getReturnType() != Void.TYPE)
                        throw new IllegalStateException(m+" is not void");
                    if (m.getExceptionTypes().length != 0)
                        throw new IllegalStateException(m+" throws checked exceptions");
                    if (Modifier.isStatic(m.getModifiers()))
                        throw new IllegalStateException(m+" is static");

                    //ServletSpec 3.0 p80 If web.xml declares even one post-construct then all post-constructs
                    //in fragments must be ignored. Otherwise, they are additive.
                    MetaData metaData = _context.getMetaData();
                    Origin origin = metaData.getOrigin("post-construct");
                    if (origin != null &&
                        (origin == Origin.WebXml ||
                         origin == Origin.WebDefaults ||
                         origin == Origin.WebOverride))
                        return;

                    PostConstructCallback callback = new PostConstructCallback();
                    callback.setTarget(clazz.getName(), m.getName());
                    LifeCycleCallbackCollection lifecycles = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
                    if (lifecycles == null)
                    {
                        lifecycles = new LifeCycleCallbackCollection();
                        _context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION,lifecycles);
                    }
                    lifecycles.add(callback);
                }
            }
        }
    }
    
    /** 
     * Check if the given class is permitted to have PostConstruct annotation.
     * @param c the class
     * @return true if the spec permits the class to have PostConstruct, false otherwise
     */
    public boolean supportsPostConstruct (Class c)
    {
        if (javax.servlet.Servlet.class.isAssignableFrom(c) ||
                javax.servlet.Filter.class.isAssignableFrom(c) || 
                javax.servlet.ServletContextListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletContextAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionIdListener.class.isAssignableFrom(c) ||
                javax.servlet.AsyncListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpUpgradeHandler.class.isAssignableFrom(c))
            return true;
        
        return false;
    }
}
