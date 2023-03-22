//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.annotations;

import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebListenerAnnotationHandler extends AbstractDiscoverableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(WebListenerAnnotationHandler.class);

    public WebListenerAnnotationHandler(WebAppContext context)
    {
        super(context);
    }

    @Override
    public void handle(AnnotationParser.ClassInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebListener".equals(annotationName))
            return;

        WebListenerAnnotation wlAnnotation = new WebListenerAnnotation(_context, info.getClassName(), info.getContainingResource());
        addAnnotation(wlAnnotation);
    }

    @Override
    public void handle(AnnotationParser.FieldInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebListener".equals(annotationName))
            return;
        LOG.warn("@WebListener is not applicable to fields: {}.{}", info.getClassInfo().getClassName(), info.getFieldName());
    }

    @Override
    public void handle(AnnotationParser.MethodInfo info, String annotationName)
    {
        if (annotationName == null || !"jakarta.servlet.annotation.WebListener".equals(annotationName))
            return;
        LOG.warn("@WebListener is not applicable to methods: {}.{} {}", info.getClassInfo().getClassName(), info.getMethodName(), info.getSignature());
    }
}
