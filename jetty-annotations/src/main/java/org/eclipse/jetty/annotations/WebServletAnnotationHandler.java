//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * WebServletAnnotationHandler
 *
 * Process a WebServlet annotation on a class.
 */
public class WebServletAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(WebServletAnnotationHandler.class);

    public WebServletAnnotationHandler(WebAppContext context)
    {
        super(context);
    }

    /**
     * Handle discovering a WebServlet annotation.
     */
    @Override
    public void handle(ClassInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebServlet".equals(annotationName))
            return;

        WebServletAnnotation annotation = new WebServletAnnotation(_context, info.getClassName(), info.getContainingResource());
        addAnnotation(annotation);
    }

    @Override
    public void handle(FieldInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebServlet".equals(annotationName))
            return;

        LOG.warn("@WebServlet annotation not supported for fields");
    }

    @Override
    public void handle(MethodInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebServlet".equals(annotationName))
            return;

        LOG.warn("@WebServlet annotation not supported for methods");
    }
}
