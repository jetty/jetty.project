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

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class PreDestroyAnnotationHandler implements AnnotationHandler
{
    WebAppContext _wac;
    LifeCycleCallbackCollection _callbacks;
    
    public PreDestroyAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
        _callbacks = (LifeCycleCallbackCollection)_wac.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
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

    public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        try
        {
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);

            if (args.length != 0)
            {
                Log.warn("Skipping PreDestroy annotation on "+className+"."+methodName+": has parameters");
                return;
            }
            if (org.objectweb.asm.Type.getReturnType(desc) != org.objectweb.asm.Type.VOID_TYPE)
            {
                Log.warn("Skipping PreDestroy annotation on "+className+"."+methodName+": is not void");
                return;
            }

            if (exceptions != null && exceptions.length != 0)
            {
                Log.warn("Skipping PreDestroy annotation on "+className+"."+methodName+": throws checked exceptions");
                return;
            }

            if ((access & org.objectweb.asm.Opcodes.ACC_STATIC) > 0)
            {
                Log.warn("Skipping PreDestroy annotation on "+className+"."+methodName+": is static");
                return;
            }

            PreDestroyCallback callback = new PreDestroyCallback(); 
            callback.setTarget(className, methodName);
            _callbacks.add(callback);
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
}
