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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.annotation.PreDestroy;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.webapp.WebAppContext;

public class PreDestroyAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    WebAppContext _wac;
    LifeCycleCallbackCollection _callbacks;
    
    public PreDestroyAnnotationHandler (WebAppContext wac)
    {
        super(true);
        _wac = wac;
        _callbacks = (LifeCycleCallbackCollection)_wac.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
    }

    public void doHandle(Class clazz)
    {
        
        //Check that the PreDestroy is on a class that we're interested in
        if (Util.isServletType(clazz))
        {
            Method[] methods = clazz.getDeclaredMethods();
            for (int i=0; i<methods.length; i++)
            {
                Method m = (Method)methods[i];
                if (m.isAnnotationPresent(PreDestroy.class))
                {
                    if (m.getParameterTypes().length != 0)
                        throw new IllegalStateException(m+" has parameters");
                    if (m.getReturnType() != Void.TYPE)
                        throw new IllegalStateException(m+" is not void");
                    if (m.getExceptionTypes().length != 0)
                        throw new IllegalStateException(m+" throws checked exceptions");
                    if (Modifier.isStatic(m.getModifiers()))
                        throw new IllegalStateException(m+" is static");

                    PreDestroyCallback callback = new PreDestroyCallback();
                    callback.setTarget(clazz.getName(), m.getName());
                    _callbacks.add(callback);
                }
            }
        }
    }
}
