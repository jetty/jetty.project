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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AnnotationIntrospector
 * Introspects a class to find various types of
 * annotations as defined by the servlet specification.
 */
public class AnnotationIntrospector
{
    private final Set<Class<?>> _introspectedClasses = new HashSet<>();
    private final List<IntrospectableAnnotationHandler> _handlers = new ArrayList<IntrospectableAnnotationHandler>();

    /**
     * IntrospectableAnnotationHandler
     *
     * Interface for all handlers that wish to introspect a class to find a particular annotation
     */
    public interface IntrospectableAnnotationHandler
    {
        void handle(Class<?> clazz);
    }

    /**
     * AbstractIntrospectableAnnotationHandler
     *
     * Base class for handlers that introspect a class to find a particular annotation.
     * A handler can optionally introspect the parent hierarchy of a class.
     */
    public abstract static class AbstractIntrospectableAnnotationHandler implements IntrospectableAnnotationHandler
    {
        private boolean _introspectAncestors;

        public abstract void doHandle(Class<?> clazz);

        public AbstractIntrospectableAnnotationHandler(boolean introspectAncestors)
        {
            _introspectAncestors = introspectAncestors;
        }

        @Override
        public void handle(Class<?> clazz)
        {
            Class<?> c = clazz;

            //process the whole inheritance hierarchy for the class
            while (c != null && (!c.equals(Object.class)))
            {
                doHandle(c);
                if (!_introspectAncestors)
                    break;

                c = c.getSuperclass();
            }
        }
    }

    public void registerHandler(IntrospectableAnnotationHandler handler)
    {
        _handlers.add(handler);
    }

    public void introspect(Class<?> clazz)
    {
        if (_handlers == null)
            return;
        if (clazz == null)
            return;

        synchronized (_introspectedClasses)
        {
            //Synchronize on the set of already introspected classes.
            //This ensures that only 1 thread can be introspecting, and that
            //thread must have fully finished generating the products of
            //introspection before another thread is allowed in.
            //We remember the classes that we have introspected to avoid
            //reprocessing the same class.
            if (_introspectedClasses.add(clazz))
            {
                for (IntrospectableAnnotationHandler handler : _handlers)
                {
                    try
                    {
                        handler.handle(clazz);
                    }
                    catch (RuntimeException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
