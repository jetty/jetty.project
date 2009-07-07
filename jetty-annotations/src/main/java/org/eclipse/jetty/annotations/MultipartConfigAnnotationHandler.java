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
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNode;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class MultipartConfigAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;

    public MultipartConfigAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }

    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<AnnotationNode> values)
    {
        boolean existing = false;
        ServletHolder holder = null;
        ServletHolder[] holders = _wac.getServletHandler().getServlets();
        for (ServletHolder h : holders)
        {
            if (className.equals(h.getClassName()))
            {
                holder = h;
                existing = true;
            }
        }
        if (holder == null)
        {
            holder = new ServletHolder();
            holder.setClassName(className);
        }
        
        //TODO set multipart config on the holder
        
        if (!existing)
            _wac.getServletHandler().addServlet(holder);
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<AnnotationNode> values)
    {
        Log.warn ("@MultipartConfig is not valid for fields: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<AnnotationNode> values)
    {
        Log.warn ("@MultipartConfig is not valid for methods: "+className+"."+methodName+" "+signature);
    }

}
