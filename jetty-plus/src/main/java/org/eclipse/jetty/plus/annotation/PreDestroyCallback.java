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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * PreDestroyCallback
 *
 *
 */
public class PreDestroyCallback extends LifeCycleCallback
{
    private static final Logger LOG = Log.getLogger(PreDestroyCallback.class);


    /** 
     * Commons Annotations Specification section 2.6:
     * - no params to method
     * - returns void
     * - no checked exceptions
     * - not static
     * @see org.eclipse.jetty.plus.annotation.LifeCycleCallback#validate(java.lang.Class, java.lang.reflect.Method)
     */
    public void validate(Class<?> clazz, Method method)
    {        

        if (method.getExceptionTypes().length > 0)
            throw new IllegalArgumentException(clazz.getName()+"."+method.getName()+ " cannot not throw a checked exception");
        
        if (!method.getReturnType().equals(Void.TYPE))
            throw new IllegalArgumentException(clazz.getName()+"."+method.getName()+ " cannot not have a return type");
        
        if (Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException(clazz.getName()+"."+method.getName()+ " cannot be static");
        
    }

    
    public void callback(Object instance)
    {
        try
        {
            super.callback(instance);
        }
        catch (Exception e)
        {
            LOG.warn("Ignoring exception thrown on preDestroy call to "+getTargetClass()+"."+getTarget().getName(), e);
        }
    }
    
    public boolean equals(Object o)
    {
        if (super.equals(o) && (o instanceof PreDestroyCallback))
            return true;
        return false;
    }
}
