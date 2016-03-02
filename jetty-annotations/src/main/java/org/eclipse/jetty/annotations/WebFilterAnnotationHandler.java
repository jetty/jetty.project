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

package org.eclipse.jetty.annotations;

import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.annotations.AnnotationParser.FieldInfo;
import org.eclipse.jetty.annotations.AnnotationParser.MethodInfo;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebFilterAnnotationHandler
 *
 *
 */
public class WebFilterAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(WebFilterAnnotationHandler.class);

    public WebFilterAnnotationHandler (WebAppContext context)
    {
        super(context);
    }
   
    
    @Override
    public void handle(ClassInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebFilter".equals(annotationName))
            return;
        
        WebFilterAnnotation wfAnnotation = new WebFilterAnnotation(_context, info.getClassName(), info.getContainingResource());
        addAnnotation(wfAnnotation);
    }

    @Override
    public void handle(FieldInfo info, String annotationName)
    {  
        if (annotationName == null || !"javax.servlet.annotation.WebFilter".equals(annotationName))
            return;
        LOG.warn ("@WebFilter not applicable for fields: "+info.getClassInfo().getClassName()+"."+info.getFieldName());
    }

    @Override
    public void handle(MethodInfo info, String annotationName)
    {  
        if (annotationName == null || !"javax.servlet.annotation.WebFilter".equals(annotationName))
            return;
        LOG.warn ("@WebFilter not applicable for methods: "+info.getClassInfo().getClassName()+"."+info.getMethodName()+" "+info.getSignature());
    }
}
