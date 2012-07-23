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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.annotation.Managed;
import org.eclipse.jetty.util.annotation.Name;

public class ContextHandlerMBean extends AbstractHandlerMBean
{
    public ContextHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Managed(value="Map of context attributes", readonly=true, attribute=true)
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
    
    @Managed(value="Set context attribute", impact="ACTION")
    public void setContextAttribute(@Name(value = "name", description="attribute name") String name, @Name(value = "value", description="attribute value") Object value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name,value);
    }
    
    @Managed(value="Set context attribute", impact="ACTION")
    public void setContextAttribute(@Name(value = "name", description="attribute name") String name, @Name(value = "value", description="attribute value") String value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name,value);
    }
    
    @Managed(value="Remove context attribute", impact="ACTION")
    public void removeContextAttribute(@Name(value = "name", description="attribute name") String name)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.removeAttribute(name);
    }
}
