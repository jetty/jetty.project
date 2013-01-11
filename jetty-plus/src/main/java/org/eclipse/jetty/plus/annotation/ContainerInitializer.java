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

package org.eclipse.jetty.plus.annotation;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.webapp.WebAppContext;

public class ContainerInitializer
{
    protected ServletContainerInitializer _target;
    protected Class[] _interestedTypes;
    protected Set<String> _applicableTypeNames;
    protected Set<String> _annotatedTypeNames;

    
    public void setTarget (ServletContainerInitializer target)
    {
        _target = target;
    }
    
    public ServletContainerInitializer getTarget ()
    {
        return _target;
    }

    public Class[] getInterestedTypes ()
    {
        return _interestedTypes;
    }
    
    public void setInterestedTypes (Class[] interestedTypes)
    {
        _interestedTypes = interestedTypes;
    }
    
    /**
     * A class has been found that has an annotation of interest 
     * to this initializer.
     * @param className
     */
    public void addAnnotatedTypeName (String className)
    {
        if (_annotatedTypeNames == null)
            _annotatedTypeNames = new HashSet<String>();
        _annotatedTypeNames.add(className);
    }
    
    public Set<String> getAnnotatedTypeNames ()
    {
        return _annotatedTypeNames;
    }
    
    public void addApplicableTypeName (String className)
    {
        if (_applicableTypeNames == null)
            _applicableTypeNames = new HashSet<String>();
        _applicableTypeNames.add(className);
    }
    
    public Set<String> getApplicableTypeNames ()
    {
        return _applicableTypeNames;
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
                if (_applicableTypeNames != null)
                {
                    for (String s : _applicableTypeNames)
                        classes.add(Loader.loadClass(context.getClass(), s));
                }

                _target.onStartup(classes, context.getServletContext());
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldLoader);
            }
        }
    }
}
