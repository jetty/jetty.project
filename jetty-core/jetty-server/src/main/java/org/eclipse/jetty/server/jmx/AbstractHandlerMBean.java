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

package org.eclipse.jetty.server.jmx;

import java.util.List;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

// TODO: can this handle inner classes like Handler.Abstract, etc.?
public class AbstractHandlerMBean extends ObjectMBean
{
    public AbstractHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public String getObjectContextBasis()
    {
        if (_managed != null)
        {
            String basis = null;
            if (_managed instanceof ContextHandler contextHandler)
            {
                String contextName = getContextName(contextHandler);
                if (contextName == null)
                    contextName = contextHandler.getDisplayName();
                if (contextName != null)
                    return contextName;
            }
            else if (_managed instanceof Handler.Abstract handler)
            {
                Server server = handler.getServer();
                if (server != null)
                {
                    ContextHandler context = server.getContainer(handler, ContextHandler.class);
                    if (context != null)
                        basis = getContextName(context);
                }
            }
            if (basis != null)
                return basis;
        }
        return super.getObjectContextBasis();
    }

    protected String getContextName(ContextHandler context)
    {
        String name = null;

        if (context.getContextPath() != null && context.getContextPath().length() > 0)
        {
            int idx = context.getContextPath().lastIndexOf('/');
            name = idx < 0 ? context.getContextPath() : context.getContextPath().substring(++idx);
            if (name == null || name.length() == 0)
                name = "ROOT";
        }

        if (name == null && context.getResourceBase() != null)
            name = context.getResourceBase().toFile().getName();

        List<String> vhosts = context.getVirtualHosts();
        if (vhosts.size() > 0)
            name = '"' + name + "@" + vhosts.get(0) + '"';

        return name;
    }
}
