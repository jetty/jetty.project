//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
    public PostConstructAnnotationHandler(WebAppContext wac)
    {
        super(true, wac);
    }

    @Override
    public void doHandle(Class clazz)
    {
        //Check that the PostConstruct is on a class that we're interested in
        if (supportsPostConstruct(clazz))
        {
            Method[] methods = clazz.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++)
            {
                Method m = (Method)methods[i];
                if (m.isAnnotationPresent(PostConstruct.class))
                {
                    if (m.getParameterCount() != 0)
                        throw new IllegalStateException(m + " has parameters");
                    if (m.getReturnType() != Void.TYPE)
                        throw new IllegalStateException(m + " is not void");
                    if (m.getExceptionTypes().length != 0)
                        throw new IllegalStateException(m + " throws checked exceptions");
                    if (Modifier.isStatic(m.getModifiers()))
                        throw new IllegalStateException(m + " is static");

                    //ServletSpec 3.0 p80 If web.xml declares even one post-construct then all post-constructs
                    //in fragments must be ignored. Otherwise, they are additive.
                    MetaData metaData = _context.getMetaData();
                    Origin origin = metaData.getOrigin("post-construct");
                    if (origin != null &&
                        (origin == Origin.WebXml ||
                            origin == Origin.WebDefaults ||
                            origin == Origin.WebOverride))
                        return;

                    PostConstructCallback callback = new PostConstructCallback(clazz, m.getName());
                    LifeCycleCallbackCollection lifecycles = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
                    if (lifecycles == null)
                    {
                        lifecycles = new LifeCycleCallbackCollection();
                        _context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, lifecycles);
                    }
                    lifecycles.add(callback);
                }
            }
        }
    }

    /**
     * Check if the given class is permitted to have PostConstruct annotation.
     *
     * @param c the class
     * @return true if the spec permits the class to have PostConstruct, false otherwise
     */
    public boolean supportsPostConstruct(Class c)
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
