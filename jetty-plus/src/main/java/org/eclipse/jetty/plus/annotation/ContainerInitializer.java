//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContainerInitializer;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

public class ContainerInitializer
{
    private static final Logger LOG = Log.getLogger(ContainerInitializer.class);
    
    final protected ServletContainerInitializer _target;
    final protected Class[] _interestedTypes;
    protected Set<String> _applicableTypeNames = new ConcurrentHashSet<String>();
    protected Set<String> _annotatedTypeNames = new ConcurrentHashSet<String>();


    public ContainerInitializer (ServletContainerInitializer target, Class[] classes)
    {
        _target = target;
        _interestedTypes = classes;
    }

    public ServletContainerInitializer getTarget ()
    {
        return _target;
    }

    public Class[] getInterestedTypes ()
    {
        return _interestedTypes;
    }


    /**
     * A class has been found that has an annotation of interest
     * to this initializer.
     * @param className
     */
    public void addAnnotatedTypeName (String className)
    {
        _annotatedTypeNames.add(className);
    }

    public Set<String> getAnnotatedTypeNames ()
    {
        return Collections.unmodifiableSet(_annotatedTypeNames);
    }

    public void addApplicableTypeName (String className)
    {
        _applicableTypeNames.add(className);
    }

    public Set<String> getApplicableTypeNames ()
    {
        return Collections.unmodifiableSet(_applicableTypeNames);
    }


    public void callStartup(WebAppContext context)
    throws Exception
    {
        if (_target != null)
        {
            Set<Class<?>> classes = new HashSet<Class<?>>();

            ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(context.getClassLoader());

            try
            {
                for (String s : _applicableTypeNames)
                    classes.add(Loader.loadClass(context.getClass(), s));

                context.getServletContext().setExtendedListenerTypes(true);
                if (LOG.isDebugEnabled())
                {
                    long start = System.nanoTime();
                    _target.onStartup(classes, context.getServletContext());
                    LOG.debug("ContainerInitializer {} called in {}ms", _target.getClass().getName(), TimeUnit.MILLISECONDS.convert(System.nanoTime()-start, TimeUnit.NANOSECONDS));
                }
                else
                    _target.onStartup(classes, context.getServletContext());
            }
            finally
            { 
                context.getServletContext().setExtendedListenerTypes(false);
                Thread.currentThread().setContextClassLoader(oldLoader);
            }
        }
    }
}
