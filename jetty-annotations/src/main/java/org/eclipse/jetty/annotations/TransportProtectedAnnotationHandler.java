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

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;

public class TransportProtectedAnnotationHandler extends AbstractSecurityAnnotationHandler
{

    
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        Class clazz;
        try
        {
            clazz = Loader.loadClass(null, className);
        }
        catch (Exception e)
        {
            Log.warn(e);
            return;
        }

        //Sec. 13.14 Access Control Annotations
        //TransportProtected may only be specified on a class that implements javax.servlet.Servlet
        if (!Servlet.class.isAssignableFrom(clazz))
        {
            Log.warn(clazz.getName()+" is not assignable from javax.servlet.http.HttpServlet");
            return;
        }
 
        //TransportProtected is equivalent to a <user-data-constraint><transport-guarantee> element in web.xml:
        //true == CONFIDENTIAL
        //false == NONE
        if (values != null && values.size() == 1)
        {
            org.eclipse.jetty.plus.annotation.TransportProtected tp = new org.eclipse.jetty.plus.annotation.TransportProtected(className);
            tp.setValue((Boolean)values.get(0).getValue());
        }
        //Need to relate the name of the class to a <security-constraint> somehow ?!
 
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn("TransportProtected annotation not permitted on field - ignoring");
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        //TransportProtected is equivalent to a <user-data-constraint><transport-guarantee> element in web.xml
        //Got name of class with the annotation, and we can get the value of annotation from values:
        //true == CONFIDENTIAL
        //false == NONE
       if (!isHttpMethod(methodName))
       {
            Log.warn ("TransportProtected annotation not permitted on "+methodName+" - ignoring");
            return;
       }
       org.eclipse.jetty.plus.annotation.TransportProtected tp = new org.eclipse.jetty.plus.annotation.TransportProtected(className);
       tp.setMethodName(methodName);
       tp.setValue((Boolean)values.get(0).getValue());
    }

}
