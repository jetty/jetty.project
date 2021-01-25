//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
        httpClient.start();

        try
        {
            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
            // Adding MBeanContainer as a bean will trigger the registration of MBeans.
            httpClient.addBean(mbeanContainer);

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
