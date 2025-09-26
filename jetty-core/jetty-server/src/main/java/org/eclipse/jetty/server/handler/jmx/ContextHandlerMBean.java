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

package org.eclipse.jetty.server.handler.jmx;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.jmx.Handler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;

@ManagedObject("ContextHandler mbean wrapper")
public class ContextHandlerMBean extends Handler.AbstractMBean
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
        ContextHandler contextHandler = getManagedObject();
        String ctxPath = contextHandler.getContextPath();
        if (StringUtil.isBlank(ctxPath) || "/".equals(ctxPath))
            ctxPath = "ROOT";
        // Replace / as it is used as a parent separator.
        String context = ctxPath.replaceAll("/", "_");

        List<String> vHosts = contextHandler.getVirtualHosts();
        if (vHosts != null && !vHosts.isEmpty())
            context = context + "@" + vHosts.get(0);

        return context;
    }

    @ManagedAttribute("Map of context attributes")
    public Map<String, String> getContextAttributes()
    {
        Map<String, String> map = new TreeMap<>();
        ContextHandler.ScopedContext context = getManagedObject().getContext();
        for (String name : context.getAttributeNameSet())
            map.put(name, String.valueOf(context.getAttribute(name)));
        return map;
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") Object value)
    {
        getManagedObject().getContext().setAttribute(name, value);
    }

    @ManagedOperation(value = "Set context attribute", impact = "ACTION")
    public void setContextAttribute(@Name(value = "name", description = "attribute name") String name, @Name(value = "value", description = "attribute value") String value)
    {
        getManagedObject().getContext().setAttribute(name, value);
    }

    @ManagedOperation(value = "Remove context attribute", impact = "ACTION")
    public void removeContextAttribute(@Name(value = "name", description = "attribute name") String name)
    {
        getManagedObject().getContext().removeAttribute(name);
    }
}
