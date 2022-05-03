//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.annotations;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebServletAnnotationHandler
 *
 * Process a WebServlet annotation on a class.
 */
public class WebServletAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(WebServletAnnotationHandler.class);

    public WebServletAnnotationHandler(WebAppContext context)
    {
        super(context);
    }

    /**
     * Handle discovering a WebServlet annotation.
     */
    @Override
    public void handle(AnnotationParser.ClassInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebServlet".equals(annotationName))
            return;

        WebServletAnnotation annotation = new WebServletAnnotation(_context, info.getClassName(), info.getContainingResource());
        addAnnotation(annotation);
    }

    @Override
    public void handle(AnnotationParser.FieldInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebServlet".equals(annotationName))
            return;

        LOG.warn("@WebServlet annotation not supported for fields");
    }

    @Override
    public void handle(AnnotationParser.MethodInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebServlet".equals(annotationName))
            return;

        LOG.warn("@WebServlet annotation not supported for methods");
    }
}
