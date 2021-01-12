//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import javax.management.JMX;
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

        JettyLoggerFactoryMBean mbean = JMX.newMBeanProxy(mbeanServer, objectName, JettyLoggerFactoryMBean.class);

        // Only the root logger.
        assertEquals(1, mbean.getLoggerCount());

        JettyLoggerFactory loggerFactory = (JettyLoggerFactory)LoggerFactory.getILoggerFactory();
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
