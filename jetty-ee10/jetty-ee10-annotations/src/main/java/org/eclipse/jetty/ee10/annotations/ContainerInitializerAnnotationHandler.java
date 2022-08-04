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

import java.util.Objects;

import org.eclipse.jetty.ee10.servlet.ServletContainerInitializerHolder;

/**
 * ContainerInitializerAnnotationHandler
 * <p>
 * Discovers classes that contain the specified annotation, either at class or
 * method level. The specified annotation is derived from an <code>&#064;HandlesTypes</code> on
 * a ServletContainerInitializer class.
 */
public class ContainerInitializerAnnotationHandler extends AnnotationParser.AbstractHandler
{
    final ServletContainerInitializerHolder _holder;
    final Class<?> _annotation;

    public ContainerInitializerAnnotationHandler(ServletContainerInitializerHolder holder, Class<?> annotation)
    {
        _holder = Objects.requireNonNull(holder);
        _annotation = Objects.requireNonNull(annotation);
    }

    /**
     * Handle finding a class that is annotated with the annotation we were constructed with.
     *
     * @see AnnotationParser.Handler#handle(AnnotationParser.ClassInfo, String)
     */
    @Override
    public void handle(AnnotationParser.ClassInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;

        _holder.addStartupClasses(info.getClassName());
    }

    /**
     * Handle finding a field that is annotated with the annotation we were constructed with.
     *
     * @see AnnotationParser.Handler#handle(AnnotationParser.FieldInfo, String)
     */
    @Override
    public void handle(AnnotationParser.FieldInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;

        _holder.addStartupClasses(info.getClassInfo().getClassName());
    }

    /**
     * Handle finding a method that is annotated with the annotation we were constructed with.
     *
     * @see AnnotationParser.Handler#handle(AnnotationParser.MethodInfo, String)
     */
    @Override
    public void handle(AnnotationParser.MethodInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;
        _holder.addStartupClasses(info.getClassInfo().getClassName());
    }
}
