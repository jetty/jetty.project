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

import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;

/**
 * ContainerInitializerAnnotationHandler
 *
 *  Discovers classes that contain the specified annotation, either at class or
 *  method level. The specified annotation is derived from an @HandlesTypes on
 *  a ServletContainerInitializer class. 
 */
public class ContainerInitializerAnnotationHandler implements DiscoverableAnnotationHandler
{
    ContainerInitializer _initializer;
    Class _annotation;

    public ContainerInitializerAnnotationHandler (ContainerInitializer initializer, Class annotation)
    {
        _initializer = initializer;
        _annotation = annotation;
    }
    
    /** 
     * Handle finding a class that is annotated with the annotation we were constructed with.
     * @see org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotationName,
                            List<Value> values)
    { 
         _initializer.addAnnotatedTypeName(className);
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
       _initializer.addAnnotatedTypeName(className);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
       _initializer.addAnnotatedTypeName(className);
    }

    @Override
    public String getAnnotationName()
    {
       return _annotation.getName();
    }
    
    public ContainerInitializer getContainerInitializer()
    {
        return _initializer;
    }

}
