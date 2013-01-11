//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebServletAnnotationHandler
 *
 * Process a WebServlet annotation on a class.
 * 
 */
public class WebServletAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(WebServletAnnotationHandler.class);
    
    public WebServletAnnotationHandler (WebAppContext context)
    {
        super(context);
    }
    
    public WebServletAnnotationHandler (WebAppContext context, List<DiscoveredAnnotation> list)
    {
        super(context, list);
    }
    
    
    /** 
     * Handle discovering a WebServlet annotation.
     * 
     *  
     * @see org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotationName,
                            List<Value> values)
    {
        if (!"javax.servlet.annotation.WebServlet".equals(annotationName))
            return;    
       
        WebServletAnnotation annotation = new WebServletAnnotation (_context, className, _resource);
        addAnnotation(annotation);
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        LOG.warn ("@WebServlet annotation not supported for fields");
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        LOG.warn ("@WebServlet annotation not supported for methods");
    }


    @Override
    public String getAnnotationName()
    {
        return "javax.servlet.annotation.WebServlet";
    }    
}
