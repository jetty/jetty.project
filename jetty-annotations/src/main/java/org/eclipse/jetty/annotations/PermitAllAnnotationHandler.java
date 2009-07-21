// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class PermitAllAnnotationHandler extends AbstractSecurityAnnotationHandler
{
    
    

    public PermitAllAnnotationHandler(WebAppContext context)
    {
        super(context);
    }

    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        if (exists(className, AUTH_TYPES))
        {
            Log.warn("Multiple conflicting security annotations for "+className);
            return;
        }
        org.eclipse.jetty.plus.annotation.PermitAll permitAll = new org.eclipse.jetty.plus.annotation.PermitAll(className);
        add(permitAll);
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
       Log.warn("PermitAll annotation not permitted on field "+fieldName+ "- ignoring");
    }

 
    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        if (!isHttpMethod(methodName))
        {
             Log.warn ("PermitAll annotation not permitted on "+methodName+" - ignoring");
             return;
        }
        
        if (exists(className, methodName, AUTH_TYPES))
        {
            Log.warn("Multiple conflicting security annotations for "+className+"."+methodName);
            return;
        }
        org.eclipse.jetty.plus.annotation.PermitAll permitAll = new org.eclipse.jetty.plus.annotation.PermitAll(className);
        permitAll.setMethodName(methodName);
        add(permitAll);
    }

}
