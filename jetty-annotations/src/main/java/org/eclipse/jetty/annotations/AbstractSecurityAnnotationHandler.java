// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.AbstractAccessControl;
import org.eclipse.jetty.webapp.WebAppContext;

public abstract class AbstractSecurityAnnotationHandler implements AnnotationHandler
{
    public static final String SECURITY_ANNOTATIONS = "org.eclipse.jetty.securityAnnotations";
    public static final Class[] AUTH_TYPES = new Class[]{org.eclipse.jetty.plus.annotation.RolesAllowed.class, 
                                                         org.eclipse.jetty.plus.annotation.PermitAll.class, 
                                                         org.eclipse.jetty.plus.annotation.DenyAll.class};
    public static final Class[] TRANSPORT_TYPES = new Class[]{org.eclipse.jetty.plus.annotation.TransportProtected.class};
    
    protected WebAppContext _context;
    protected HashMap<String,List<AbstractAccessControl>> _discoveredAccessControls = new HashMap<String, List<AbstractAccessControl>>();
    
    public AbstractSecurityAnnotationHandler(WebAppContext context)
    {
        _context = context;
        _context.setAttribute(SECURITY_ANNOTATIONS, _discoveredAccessControls);
    }
    
    
    public void add (AbstractAccessControl c)
    {
        List<AbstractAccessControl> list = _discoveredAccessControls.get(c.getClassName());
        if (list == null)
        {
            list = new ArrayList<AbstractAccessControl>();
            _discoveredAccessControls.put(c.getClassName(), list);
        }
        list.add(c);
    }
    
    public boolean exists (String className, Class[] accessControlTypes)
    {
        List<AbstractAccessControl> list = _discoveredAccessControls.get(className);
        if (list == null)
            return false;
        
        boolean exists = false;
        for (AbstractAccessControl control : list)
        {
            if (!control.isMethodType())
            {
                for (Class c : accessControlTypes)
                {
                    if (c.isInstance(control))
                    {
                        exists = true;
                        break;
                    }
                }
            }
        }
        return exists;
    }
    
    public boolean exists (String className, String methodName, Class[] accessControlTypes)
    {
        List<AbstractAccessControl> list = _discoveredAccessControls.get(className);
        if (list == null)
            return false;
        
        boolean exists = false;
        for (AbstractAccessControl control : list)
        {
            if (control.isMethodType() && control.getMethodName().equals(methodName))
            {
                for (Class c : accessControlTypes)
                {
                    if (c.isInstance(control))
                    {
                        exists = true;
                        break;
                    }
                }
            }
        }
        return exists;
    }
    
    public boolean isHttpMethod (String methodName)
    {
        if ("doGet".equals(methodName) ||
                "doPost".equals(methodName) ||
                "doDelete".equals(methodName) ||
                "doHead".equals(methodName) ||
                "doOptions".equals(methodName) ||
                "doPut".equals(methodName) ||
                "doTrace".equals(methodName))
        {
            return true;
        }

        return false;
    }

}
