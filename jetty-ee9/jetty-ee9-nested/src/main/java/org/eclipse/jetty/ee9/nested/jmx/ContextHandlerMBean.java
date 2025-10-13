//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested.jmx;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
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

    @Override
    public ContextHandler getManagedObject()
    {
        return (ContextHandler)super.getManagedObject();
    }

    @Override
    public String getObjectNameBasis()
    {
        return getManagedObject().getDisplayName();
    }

    @Override
    public String getObjectContextBasis()
    {
        String ctxPath = getManagedObject().getContextPath();
        if (StringUtil.isBlank(ctxPath) || "/".equals(ctxPath))
            ctxPath = "ROOT";
        // Replace / as it is used as a parent separator.
        String context = ctxPath.replaceAll("/", "_");

        String[] vHosts = getManagedObject().getVirtualHosts();
        if (vHosts != null && vHosts.length > 0)
            context = context + "@" + vHosts[0];

        return context;
    }

    @ManagedAttribute("Map of context attributes")
    public Map<String, String> getContextAttributes()
    {
        Map<String, String> map = new HashMap<>();
        Attributes attrs = getManagedObject().getAttributes();
        for (String name : attrs.getAttributeNameSet())
            map.put(name, String.valueOf(attrs.getAttribute(name)));
        return map;
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") Object value)
    {
        Attributes attrs = getManagedObject().getAttributes();
        attrs.setAttribute(name, value);
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") String value)
    {
        Attributes attrs = getManagedObject().getAttributes();
        attrs.setAttribute(name, value);
    }

    @ManagedOperation(value = "Remove context attribute", impact = "ACTION")
    public void removeContextAttribute(@Name(value = "name", description = "attribute name") String name)
    {
        Attributes attrs = getManagedObject().getAttributes();
        attrs.removeAttribute(name);
    }
}
