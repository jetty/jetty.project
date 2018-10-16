//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.function;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class AnnotationPredicate implements Predicate<Method>
{
    private static final Map<Class<? extends Annotation>, Predicate<Method>>
            CACHE = new ConcurrentHashMap<>();

    /**
     * Get Predicate from Cache (add if not present)
     */
    public static Predicate<Method> get(Class<? extends Annotation> annotation)
    {
        Predicate<Method> predicate = CACHE.get(annotation);
        if (predicate == null)
        {
            predicate = new AnnotationPredicate(annotation);
            CACHE.put(annotation, predicate);
        }
        return predicate;
    }

    private final Class<? extends Annotation> annotation;

    public AnnotationPredicate(Class<? extends Annotation> annotation)
    {
        this.annotation = annotation;
    }

    @Override
    public boolean test(Method method)
    {
        return (method.getAnnotation(annotation) != null);
    }
}
