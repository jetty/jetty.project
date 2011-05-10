// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler.jmx;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;

public class ContextHandlerMBean extends ObjectMBean
{
    public ContextHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    public String getObjectNameBasis()
    {
        if (_managed!=null && _managed instanceof ContextHandler)
        {
            ContextHandler context = (ContextHandler)_managed;
            String name = context.getDisplayName();
            if (name!=null)
                return name;
            
            if (context.getBaseResource()!=null && context.getBaseResource().getName().length()>1)
                return context.getBaseResource().getName();
        }
        return super.getObjectNameBasis();
    }
    
    public Map getContextAttributes()
    {
        Map map = new HashMap();
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        Enumeration en = attrs.getAttributeNames();
        while (en.hasMoreElements())
        {
            String name = (String)en.nextElement();
            Object value = attrs.getAttribute(name);
            map.put(name,value);
        }
        return map;
    }
    
    public void setContextAttribute(String name, Object value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name,value);
    }
    
    public void setContextAttribute(String name, String value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name,value);
    }
    
    public void removeContextAttribute(String name)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.removeAttribute(name);
    }
}
