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

public class TransportProtectedAnnotationHandler extends AbstractSecurityAnnotationHandler
{
        
    public TransportProtectedAnnotationHandler(WebAppContext context)
    {
        super(context);
    }
    
    

    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        //TransportProtected is equivalent to a <user-data-constraint><transport-guarantee> element in web.xml:
        //true == CONFIDENTIAL
        //false == NONE
        if (exists(className, TRANSPORT_TYPES))
        {
            Log.warn("Conflicting TransportProtected annotation for "+className);
            return;
        }
        
        if (values != null && values.size() == 1)
        {
            org.eclipse.jetty.plus.annotation.TransportProtected tp = new org.eclipse.jetty.plus.annotation.TransportProtected(className);
            tp.setValue((Boolean)values.get(0).getValue());
            add(tp);
        }
     
 
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn("TransportProtected annotation not permitted on field - ignoring");
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {      
       if (!isHttpMethod(methodName))
       {
            Log.warn ("TransportProtected annotation not permitted on "+methodName+" - ignoring");
            return;
       } 
       
       if (exists(className, methodName, TRANSPORT_TYPES))
       {
           Log.warn("Conflicting TransportProtected annotation for "+className);
           return;
       }
       
       if (values != null && values.size() == 1)
       {
           org.eclipse.jetty.plus.annotation.TransportProtected tp = new org.eclipse.jetty.plus.annotation.TransportProtected(className);
           tp.setMethodName(methodName);
           tp.setValue((Boolean)values.get(0).getValue());
           add(tp);
       }
    }

}
