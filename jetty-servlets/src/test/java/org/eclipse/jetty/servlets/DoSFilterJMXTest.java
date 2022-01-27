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

package org.eclipse.jetty.servlets;

import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.Set;
import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DoSFilterJMXTest
{
    @Test
    public void testDoSFilterJMX() throws Exception
    {
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);
        DoSFilter filter = new DoSFilter();
        FilterHolder holder = new FilterHolder(filter);
        String name = "dos";
        holder.setName(name);
        holder.setInitParameter(DoSFilter.MANAGED_ATTR_INIT_PARAM, "true");
        context.addFilter(holder, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, name);

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        server.addBean(mbeanContainer);

        server.start();

        String domain = DoSFilter.class.getPackage().getName();
        Set<ObjectName> mbeanNames = mbeanServer.queryNames(ObjectName.getInstance(domain + ":*"), null);
        assertEquals(1, mbeanNames.size());
        ObjectName objectName = mbeanNames.iterator().next();

        boolean value = (Boolean)mbeanServer.getAttribute(objectName, "enabled");
        mbeanServer.setAttribute(objectName, new Attribute("enabled", !value));
        assertEquals(!value, filter.isEnabled());

        String whitelist = (String)mbeanServer.getAttribute(objectName, "whitelist");
        String address = "127.0.0.1";
        assertFalse(whitelist.contains(address));
        boolean result = (Boolean)mbeanServer.invoke(objectName, "addWhitelistAddress", new Object[]{address}, new String[]{
            String.class.getName()
        });
        assertTrue(result);
        whitelist = (String)mbeanServer.getAttribute(objectName, "whitelist");
        assertThat(whitelist, containsString(address));

        result = (Boolean)mbeanServer.invoke(objectName, "removeWhitelistAddress", new Object[]{address}, new String[]{
            String.class.getName()
        });
        assertTrue(result);
        whitelist = (String)mbeanServer.getAttribute(objectName, "whitelist");
        assertThat(whitelist, not(containsString(address)));

        server.stop();
    }
}
