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

package org.eclipse.jetty.client.jmx;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientJMXTest
{
    @Test
    public void testHttpClientName() throws Exception
    {
        String name = "foo";
        HttpClient httpClient = new HttpClient();
        httpClient.setName(name);

        try
        {
            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
            // Adding MBeanContainer as a bean will trigger the registration of MBeans.
            httpClient.addBean(mbeanContainer);
            httpClient.start();

            String domain = HttpClient.class.getPackage().getName();
            ObjectName pattern = new ObjectName(domain + ":type=" + HttpClient.class.getSimpleName().toLowerCase(Locale.ENGLISH) + ",*");
            Set<ObjectName> objectNames = mbeanServer.queryNames(pattern, null);
            assertEquals(1, objectNames.size());
            ObjectName objectName = objectNames.iterator().next();
            assertEquals(name, objectName.getKeyProperty("context"));

            // Verify that the context is inherited by the descendant components.
            domain = SelectorManager.class.getPackage().getName();
            pattern = new ObjectName(domain + ":*");
            objectNames = mbeanServer.queryNames(pattern, null);
            for (ObjectName oName : objectNames)
            {
                assertEquals(name, oName.getKeyProperty("context"));
            }
        }
        finally
        {
            httpClient.stop();
        }
    }
}
