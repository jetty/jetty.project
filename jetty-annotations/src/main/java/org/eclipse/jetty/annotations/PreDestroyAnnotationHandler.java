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
import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class PreDestroyAnnotationHandler implements AnnotationHandler
{
    WebAppContext _wac;
    
    public PreDestroyAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }

    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        Log.warn("@PreDestroy annotation not applicable for classes: "+className);      
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn("@PreDestroy annotation not applicable for fields: "+className+"."+fieldName);     
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_wac.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);

        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null, className);

            Method m = clazz.getDeclaredMethod(methodName, Util.convertTypes(params));
            if (!Util.isServletType(m.getDeclaringClass()))
            {
                Log.debug("Ignored "+m.getName()+" as non-servlet type");
                return;
            }
            if (m.getParameterTypes().length != 0)
                throw new IllegalStateException(m+" has parameters");
            if (m.getReturnType() != Void.TYPE)
                throw new IllegalStateException(m+" is not void");
            if (m.getExceptionTypes().length != 0)
                throw new IllegalStateException(m+" throws checked exceptions");
            if (Modifier.isStatic(m.getModifiers()))
                throw new IllegalStateException(m+" is static");

            PreDestroyCallback callback = new PreDestroyCallback(); 
            callback.setTargetClass(m.getDeclaringClass());
            callback.setTarget(m);
            callbacks.add(callback);
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
}
