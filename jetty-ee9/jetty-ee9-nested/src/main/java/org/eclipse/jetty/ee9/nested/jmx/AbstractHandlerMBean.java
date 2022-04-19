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

package org.eclipse.jetty.ee9.nested.jmx;

import java.io.IOException;

import org.eclipse.jetty.ee9.nested.AbstractHandler;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractHandlerMBean extends ObjectMBean
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractHandlerMBean.class);

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
            if (_managed instanceof ContextHandler)
            {
                ContextHandler handler = (ContextHandler)_managed;
                String context = getContextName(handler);
                if (context == null)
                    context = handler.getDisplayName();
                if (context != null)
                    return context;
            }
            else if (_managed instanceof AbstractHandler)
            {
                AbstractHandler handler = (AbstractHandler)_managed;
                Server server = handler.getServer();
                if (server != null)
                {
                    // TODO
//                    ContextHandler context =
//                        AbstractHandlerContainer.findContainerOf(server,
//                            ContextHandler.class, handler);
//
//                    if (context != null)
//                        basis = getContextName(context);
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

        if (name == null && context.getBaseResource() != null)
        {
            try
            {
                if (context.getBaseResource().getFile() != null)
                    name = context.getBaseResource().getFile().getName();
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
                name = context.getBaseResource().getName();
            }
        }

        if (context.getVirtualHosts() != null && context.getVirtualHosts().length > 0)
            name = '"' + name + "@" + context.getVirtualHosts()[0] + '"';

        return name;
    }
}
