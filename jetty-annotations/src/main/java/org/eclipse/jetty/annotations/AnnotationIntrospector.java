//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;

/**
 * AnnotationIntrospector
 *
 *
 */
public class AnnotationIntrospector
{    
    protected List<IntrospectableAnnotationHandler> _handlers = new ArrayList<IntrospectableAnnotationHandler>();
    
    
    /**
     * IntrospectableAnnotationHandler
     *
     * Interface for all handlers that wish to introspect a class to find a particular annotation
     */
    public interface IntrospectableAnnotationHandler
    {
        public void handle(Class<?> clazz);
    }
    
    
    
    /**
     * AbstractIntrospectableAnnotationHandler
     *
     * Base class for handlers that introspect a class to find a particular annotation.
     * A handler can optionally introspect the parent hierarchy of a class.
     */
    public static abstract class AbstractIntrospectableAnnotationHandler implements IntrospectableAnnotationHandler
    {
        private boolean _introspectAncestors;
        
        public abstract void doHandle(Class<?> clazz);
        
        
        public AbstractIntrospectableAnnotationHandler(boolean introspectAncestors)
        {
            _introspectAncestors = introspectAncestors;
        }
        
        public void handle(Class<?> clazz)
        {
            Class<?> c = clazz;
            
            //process the whole inheritance hierarchy for the class
            while (c!=null && (!c.equals(Object.class)))
            {
                doHandle(c);
                if (!_introspectAncestors)
                    break;
                
                c = c.getSuperclass();
            }   
        }
    }
    
    public void registerHandler (IntrospectableAnnotationHandler handler)
    {
        _handlers.add(handler);
    }
    
    public void introspect (Class<?> clazz)
    {
        if (_handlers == null)
            return;
        if (clazz == null)
            return;
        
        for (IntrospectableAnnotationHandler handler:_handlers)
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
