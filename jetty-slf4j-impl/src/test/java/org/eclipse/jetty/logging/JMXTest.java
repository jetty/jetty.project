//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JMXTest
{
    @Test
    public void testJMX() throws Exception
    {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        Properties props = new Properties();
        JettyLoggerConfiguration config = new JettyLoggerConfiguration(props);
        JettyLoggerFactory loggerFactory = new JettyLoggerFactory(config);

        ObjectName objectName = ObjectName.getInstance("org.eclipse.jetty.logging", "type", JettyLoggerFactory.class.getSimpleName().toLowerCase(Locale.ENGLISH));
        mbeanServer.registerMBean(loggerFactory, objectName);

        JettyLoggerFactoryMBean mbean = JMX.newMBeanProxy(mbeanServer, objectName, JettyLoggerFactoryMBean.class);

        // Only the root logger.
        assertEquals(1, mbean.getLoggerCount());

        JettyLogger child = loggerFactory.getJettyLogger("org.eclipse.jetty.logging");
        JettyLogger parent = loggerFactory.getJettyLogger("org.eclipse.jetty");
        assertEquals(3, mbean.getLoggerCount());

        // Names are sorted.
        List<String> expected = new ArrayList<>(Arrays.asList(JettyLogger.ROOT_LOGGER_NAME, parent.getName(), child.getName()));
        expected.sort(String::compareTo);
        String[] loggerNames = mbean.getLoggerNames();
        assertEquals(expected, Arrays.asList(loggerNames));

        // Setting the parent level should propagate to the children.
        parent.setLevel(JettyLevel.DEBUG);
        assertEquals(parent.getLevel().toString(), mbean.getLoggerLevel(child.getName()));

        // Setting the level via JMX affects the logger.
        assertTrue(mbean.setLoggerLevel(child.getName(), "INFO"));
        assertEquals(JettyLevel.INFO, child.getLevel());
    }
}
