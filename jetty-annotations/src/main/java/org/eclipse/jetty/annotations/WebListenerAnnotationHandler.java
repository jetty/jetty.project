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

package org.eclipse.jetty.annotations;

import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.annotations.AnnotationParser.FieldInfo;
import org.eclipse.jetty.annotations.AnnotationParser.MethodInfo;
import org.eclipse.jetty.webapp.WebAppContext;
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
    public void handle(ClassInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebListener".equals(annotationName))
            return;

        WebListenerAnnotation wlAnnotation = new WebListenerAnnotation(_context, info.getClassName(), info.getContainingResource());
        addAnnotation(wlAnnotation);
    }

    @Override
    public void handle(FieldInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebListener".equals(annotationName))
            return;
        LOG.warn("@WebListener is not applicable to fields: {}.{}", info.getClassInfo().getClassName(), info.getFieldName());
    }

    @Override
    public void handle(MethodInfo info, String annotationName)
    {
        if (annotationName == null || !"javax.servlet.annotation.WebListener".equals(annotationName))
            return;
        LOG.warn("@WebListener is not applicable to methods: {}.{} {}", info.getClassInfo().getClassName(), info.getMethodName(), info.getSignature());
    }
}
