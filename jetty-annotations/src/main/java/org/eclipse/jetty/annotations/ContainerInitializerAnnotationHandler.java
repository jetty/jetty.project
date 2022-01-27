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

import java.util.Objects;

import org.eclipse.jetty.annotations.AnnotationParser.AbstractHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.annotations.AnnotationParser.FieldInfo;
import org.eclipse.jetty.annotations.AnnotationParser.MethodInfo;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.servlet.ServletContainerInitializerHolder;

/**
 * ContainerInitializerAnnotationHandler
 * <p>
 * Discovers classes that contain the specified annotation, either at class or
 * method level. The specified annotation is derived from an <code>&#064;HandlesTypes</code> on
 * a ServletContainerInitializer class.
 */
public class ContainerInitializerAnnotationHandler extends AbstractHandler
{
    final ContainerInitializer _initializer;
    final ServletContainerInitializerHolder _holder;
    final Class<?> _annotation;

    @Deprecated
    public ContainerInitializerAnnotationHandler(ContainerInitializer initializer, Class<?> annotation)
    {
        _holder = null;
        _annotation = Objects.requireNonNull(annotation);
        _initializer = initializer;
    }
    
    public ContainerInitializerAnnotationHandler(ServletContainerInitializerHolder holder, Class<?> annotation)
    {
        _holder = Objects.requireNonNull(holder);
        _annotation = Objects.requireNonNull(annotation);
        _initializer = null;
    }

    /**
     * Handle finding a class that is annotated with the annotation we were constructed with.
     *
     * @see org.eclipse.jetty.annotations.AnnotationParser.Handler#handle(org.eclipse.jetty.annotations.AnnotationParser.ClassInfo, String)
     */
    @Override
    public void handle(ClassInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;

        if (_initializer != null)
            _initializer.addAnnotatedTypeName(info.getClassName());
        else
            _holder.addStartupClasses(info.getClassName());
    }

    /**
     * Handle finding a field that is annotated with the annotation we were constructed with.
     *
     * @see org.eclipse.jetty.annotations.AnnotationParser.Handler#handle(org.eclipse.jetty.annotations.AnnotationParser.FieldInfo, String)
     */
    @Override
    public void handle(FieldInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;

        if (_initializer != null)
            _initializer.addAnnotatedTypeName(info.getClassInfo().getClassName());
        else
            _holder.addStartupClasses(info.getClassInfo().getClassName());
    }

    /**
     * Handle finding a method that is annotated with the annotation we were constructed with.
     *
     * @see org.eclipse.jetty.annotations.AnnotationParser.Handler#handle(org.eclipse.jetty.annotations.AnnotationParser.MethodInfo, String)
     */
    @Override
    public void handle(MethodInfo info, String annotationName)
    {
        if (!_annotation.getName().equals(annotationName))
            return;
        if (_initializer != null)
            _initializer.addAnnotatedTypeName(info.getClassInfo().getClassName());
        else
            _holder.addStartupClasses(info.getClassInfo().getClassName());
    }

    @Deprecated
    public ContainerInitializer getContainerInitializer()
    {
        return _initializer;
    }
}
