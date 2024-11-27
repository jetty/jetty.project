//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.annotations;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import jakarta.annotation.PostConstruct;
import org.eclipse.jetty.ee11.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee11.webapp.Origin;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PostConstructCallback;

public class PostConstructAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    public PostConstructAnnotationHandler(WebAppContext wac)
    {
        super(true, wac);
    }

    @Override
    public void doHandle(Class<?> clazz)
    {
        //Check that the PostConstruct is on a class that we're interested in
        if (supportsPostConstruct(clazz))
        {
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods)
            {
                if (method.isAnnotationPresent(PostConstruct.class))
                {
                    if (method.getParameterCount() != 0)
                        throw new IllegalStateException(method + " has parameters");
                    if (method.getReturnType() != Void.TYPE)
                        throw new IllegalStateException(method + " is not void");
                    if (method.getExceptionTypes().length != 0)
                        throw new IllegalStateException(method + " throws checked exceptions");
                    if (Modifier.isStatic(method.getModifiers()))
                        throw new IllegalStateException(method + " is static");

                    //ServletSpec 3.0 p80 If web.xml declares even one post-construct then all post-constructs
                    //in fragments must be ignored. Otherwise, they are additive.
                    Origin origin = _context.getMetaData().getOrigin("post-construct");
                    if ((origin == Origin.WebXml ||
                        origin == Origin.WebDefaults ||
                        origin == Origin.WebOverride))
                        return;

                    PostConstructCallback callback = new PostConstructCallback(clazz, method.getName());
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
    public boolean supportsPostConstruct(Class<?> c)
    {
        return isAnnotatableServletClass(c);
    }
}
