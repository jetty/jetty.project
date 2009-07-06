// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.plus.annotation;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.webapp.WebAppContext;

public class ContainerInitializer
{
    protected ServletContainerInitializer _target;
    protected Class[] _interestedTypes;
    protected Set<Class<?>> _applicableClasses;

    
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
    
    public void addApplicableClass (Class c)
    {
        if (_applicableClasses == null)
            _applicableClasses = new HashSet<Class<?>>();
        _applicableClasses.add(c);
    }
    
    public Set<Class<?>> getApplicableClasses ()
    {
        return _applicableClasses;
    }
    
    
    public void callStartup(ServletContext context)
    throws Exception
    {
       if (_target != null)
           _target.onStartup(_applicableClasses, context);
    }
}
