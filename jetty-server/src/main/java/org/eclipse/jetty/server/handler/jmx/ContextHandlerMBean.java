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

package org.eclipse.jetty.server.handler.jmx;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;

@ManagedObject("ContextHandler mbean wrapper")
public class ContextHandlerMBean extends AbstractHandlerMBean
{
    public ContextHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    @ManagedAttribute("Map of context attributes")
    public Map<String, Object> getContextAttributes()
    {
        Map<String, Object> map = new HashMap<String, Object>();
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        for (String name : attrs.getAttributeNameSet())
        {
            Object value = attrs.getAttribute(name);
            map.put(name, value);
        }
        return map;
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") Object value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name, value);
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") String value)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.setAttribute(name, value);
    }

    @ManagedOperation(value = "Remove context attribute", impact = "ACTION")
    public void removeContextAttribute(@Name(value = "name", description = "attribute name") String name)
    {
        Attributes attrs = ((ContextHandler)_managed).getAttributes();
        attrs.removeAttribute(name);
    }
}
