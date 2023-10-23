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

package org.eclipse.jetty.server.jmx;

import java.util.List;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

public class Handler
{
    @ManagedObject
    public static class AbstractMBean extends ObjectMBean
    {
        public AbstractMBean(Object managedObject)
        {
            super(managedObject);
        }

        @Override
        public org.eclipse.jetty.server.Handler.Abstract getManagedObject()
        {
            return (org.eclipse.jetty.server.Handler.Abstract)super.getManagedObject();
        }

        @Override
        public String getObjectContextBasis()
        {
            if (_managed instanceof ContextHandler contextHandler)
            {
                String contextName = getContextName(contextHandler);
                if (contextName == null)
                    contextName = contextHandler.getDisplayName();
                if (contextName != null)
                    return contextName;
                return null;
            }
            else
            {
                String basis = null;
                var handler = getManagedObject();
                Server server = handler.getServer();
                if (server != null)
                {
                    ContextHandler context = server.getContainer(handler, ContextHandler.class);
                    if (context != null)
                        basis = getContextName(context);
                }
                return basis;
            }
        }

        protected String getContextName(ContextHandler context)
        {
            String name = null;

            String contextPath = context.getContextPath();
            if (contextPath != null && !contextPath.isEmpty())
            {
                int idx = contextPath.lastIndexOf('/');
                name = idx < 0 ? contextPath : contextPath.substring(++idx);
                if (name.isEmpty())
                    name = "ROOT";
            }

            if (name == null && context.getBaseResource() != null)
                name = context.getBaseResource().getPath().getFileName().toString();

            List<String> vhosts = context.getVirtualHosts();
            if (!vhosts.isEmpty())
                name = "\"%s@%s\"".formatted(name, vhosts.get(0));

            return name;
        }

        @ManagedAttribute("The invocation type")
        public String getInvocationType()
        {
            return getManagedObject().getInvocationType().name();
        }
    }
}
