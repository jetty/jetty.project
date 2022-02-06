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

package org.eclipse.jetty.logging;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JMXTest
{
    @Test
    public void testJMX() throws Exception
    {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        ObjectName objectName = ObjectName.getInstance("org.eclipse.jetty.logging", "type", JettyLoggerFactory.class.getSimpleName().toLowerCase(Locale.ENGLISH));
        mbeanServer.registerMBean(LoggerFactory.getILoggerFactory(), objectName);

        // Verify MBeanInfo
        MBeanInfo beanInfo = mbeanServer.getMBeanInfo(objectName);

        MBeanAttributeInfo[] attributeInfos = beanInfo.getAttributes();
        assertThat("MBeanAttributeInfo count", attributeInfos.length, is(2));

        MBeanAttributeInfo attr = Stream.of(attributeInfos).filter((a) -> a.getName().equals("LoggerNames")).findFirst().orElseThrow();
        assertThat("attr", attr.getDescription(), is("List of Registered Loggers by Name."));

        // Do some MBean attribute testing
        int loggerCount;

        // Only the root logger.
        loggerCount = (int)mbeanServer.getAttribute(objectName, "LoggerCount");
        assertEquals(1, loggerCount);

        JettyLoggerFactory loggerFactory = (JettyLoggerFactory)LoggerFactory.getILoggerFactory();
        JettyLogger child = loggerFactory.getJettyLogger("org.eclipse.jetty.logging");
        JettyLogger parent = loggerFactory.getJettyLogger("org.eclipse.jetty");
        loggerCount = (int)mbeanServer.getAttribute(objectName, "LoggerCount");
        assertEquals(3, loggerCount);

        // Names from JMX are sorted, so lets sort our expected list too.
        List<String> expected = new ArrayList<>(Arrays.asList(JettyLogger.ROOT_LOGGER_NAME, parent.getName(), child.getName()));
        expected.sort(String::compareTo);
        String[] loggerNames = (String[])mbeanServer.getAttribute(objectName, "LoggerNames");
        assertEquals(expected, Arrays.asList(loggerNames));

        // Do some MBean invoker testing
        String operationName;
        String[] signature;
        Object[] params;

        // Setting the parent level should propagate to the children.
        parent.setLevel(JettyLevel.DEBUG);
        operationName = "getLoggerLevel";
        signature = new String[]{String.class.getName()};
        params = new Object[]{child.getName()};
        String levelName = (String)mbeanServer.invoke(objectName, operationName, params, signature);
        assertEquals(parent.getLevel().toString(), levelName);

        // Setting the level via JMX affects the logger.
        operationName = "setLoggerLevel";
        signature = new String[]{String.class.getName(), String.class.getName()};
        params = new Object[]{child.getName(), "INFO"};
        boolean result = (boolean)mbeanServer.invoke(objectName, operationName, params, signature);
        assertTrue(result);
        assertEquals(JettyLevel.INFO, child.getLevel());
    }
}
