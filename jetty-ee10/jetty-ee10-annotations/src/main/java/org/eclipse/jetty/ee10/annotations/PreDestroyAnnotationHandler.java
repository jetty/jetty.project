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

package org.eclipse.jetty.ee10.annotations;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import jakarta.annotation.PreDestroy;
import org.eclipse.jetty.ee10.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee10.webapp.Origin;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;

public class PreDestroyAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    public PreDestroyAnnotationHandler(WebAppContext wac)
    {
        super(true, wac);
    }

    @Override
    public void doHandle(Class<?> clazz)
    {
        //Check that the PreDestroy is on a class that we're interested in
        if (supportsPreDestroy(clazz))
        {
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods)
            {
                if (method.isAnnotationPresent(PreDestroy.class))
                {
                    if (method.getParameterCount() != 0)
                        throw new IllegalStateException(method + " has parameters");
                    if (method.getReturnType() != Void.TYPE)
                        throw new IllegalStateException(method + " is not void");
                    if (method.getExceptionTypes().length != 0)
                        throw new IllegalStateException(method + " throws checked exceptions");
                    if (Modifier.isStatic(method.getModifiers()))
                        throw new IllegalStateException(method + " is static");

                    //ServletSpec 3.0 p80 If web.xml declares even one predestroy then all predestroys
                    //in fragments must be ignored. Otherwise, they are additive.
                    Origin origin = _context.getMetaData().getOrigin("pre-destroy");
                    if ((origin == Origin.WebXml ||
                        origin == Origin.WebDefaults ||
                        origin == Origin.WebOverride))
                        return;

                    PreDestroyCallback callback = new PreDestroyCallback(clazz, method.getName());

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
     * Check if the spec permits the given class to use the PreDestroy annotation.
     *
     * @param c the class
     * @return true if permitted, false otherwise
     */
    public boolean supportsPreDestroy(Class<?> c)
    {
        return isAnnotatableServletClass(c);
    }
}
