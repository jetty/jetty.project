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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.ee10.servlet.BaseHolder;
import org.eclipse.jetty.ee10.servlet.Source.Origin;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AnnotationIntrospector
 * Introspects a class to find various types of
 * annotations as defined by the servlet specification.
 */
public class AnnotationIntrospector
{
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationIntrospector.class);

    private final AutoLock _lock = new AutoLock();
    private final Set<Class<?>> _introspectedClasses = new HashSet<>();
    private final List<IntrospectableAnnotationHandler> _handlers = new ArrayList<IntrospectableAnnotationHandler>();
    private final WebAppContext _context;

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
    public abstract static class AbstractIntrospectableAnnotationHandler implements IntrospectableAnnotationHandler
    {
        protected boolean _introspectAncestors;
        protected WebAppContext _context;

        public abstract void doHandle(Class<?> clazz);

        public AbstractIntrospectableAnnotationHandler(boolean introspectAncestors, WebAppContext context)
        {
            _context = Objects.requireNonNull(context);
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

        public WebAppContext getContext()
        {
            return _context;
        }
    }

    public AnnotationIntrospector(WebAppContext context)
    {
        _context = Objects.requireNonNull(context);
    }

    public void registerHandler(IntrospectableAnnotationHandler handler)
    {
        _handlers.add(handler);
    }

    /**
     * Test if an object should be introspected for some specific types of annotations
     * like PostConstruct/PreDestroy/MultiPart etc etc.
     *
     * According to servlet 4.0, these types of annotations should only be evaluated iff any
     * of the following are true:
     * <ol>
     * <li>the object was created by the jakarta.servlet.ServletContext.createServlet/Filter/Listener method</li>
     * <li>the object comes either from a discovered annotation (WebServlet/Filter/Listener) or a declaration
     * in a descriptor AND web.xml is NOT metadata-complete AND any web-fragment.xml associated with the location of
     * the class is NOT metadata-complete</li>
     * </ol>
     *
     * We also support evaluations of these types of annotations for objects that were created directly
     * by the jetty api.
     *
     * @param o the object to check for its ability to be introspected for annotations
     * @param metaInfo meta information about the object to be introspected
     * @return true if it can be introspected according to servlet 4.0 rules
     */
    public boolean isIntrospectable(Object o, Object metaInfo)
    {
        if (o == null)
            return false; //nothing to introspect

        if (metaInfo == null)
            return true;  //no information about the object to introspect, assume introspectable

        @SuppressWarnings("rawtypes")
        BaseHolder holder = null;

        try
        {
            holder = (BaseHolder)metaInfo;
        }
        catch (ClassCastException e)
        {
            LOG.warn("Not introspectable {}", metaInfo.getClass().getName(), e);
            return true; //not the type of information we were expecting, assume introspectable
        }

        Origin origin = (holder.getSource() == null ? null : holder.getSource().getOrigin());
        if (origin == null)
            return true; //assume introspectable

        switch (origin)
        {
            case EMBEDDED:
            case JAKARTA_API:
            {
                return true; //objects created from the jetty or servlet api are always introspectable
            }
            case ANNOTATION:
            {
                return true; //we will have discovered annotations only if metadata-complete==false
            }
            default:
            {
                //must be from a descriptor. Only introspect if the descriptor with which it was associated
                //is not metadata-complete
                if (_context.getMetaData().isMetaDataComplete())
                    return false;

                String descriptorLocation = holder.getSource().getResource();
                if (descriptorLocation == null)
                    return true; //no descriptor, can't be metadata-complete
                try
                {
                    return !WebDescriptor.isMetaDataComplete(_context.getMetaData().getFragmentDescriptor(Resource.newResource(descriptorLocation)));
                }
                catch (IOException e)
                {
                    LOG.warn("Unable to get Resource for descriptor {}", descriptorLocation, e);
                    return false; //something wrong with the descriptor
                }
            }
        }
    }

    /**
     *
     */
    public void introspect(Object o, Object metaInfo)
    {
        if (!isIntrospectable(o, metaInfo))
            return;

        Class<?> clazz = o.getClass();

        try (AutoLock l = _lock.lock())
        {
            // Lock to ensure that only 1 thread can be introspecting, and that
            // thread must have fully finished generating the products of
            // the introspection before another thread is allowed in.
            // We remember the classes that we have introspected to avoid
            // reprocessing the same class.
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
