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

package org.eclipse.jetty.jmx;

import java.lang.management.ManagementFactory;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MBeanContainerLifeCycleTest
{
    private ContainerLifeCycle container;
    private MBeanServer mbeanServer;

    @BeforeEach
    public void prepare() throws Exception
    {
        container = new ContainerLifeCycle();
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        container.addBean(mbeanContainer);
        container.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        container.stop();
    }

    @Test
    public void testAddBeanRegistersMBeanRemoveBeanUnregistersMBean() throws Exception
    {
        // Adding a bean to the container should register the MBean.
        QueuedThreadPool bean = new QueuedThreadPool();
        container.addBean(bean);

        String pkg = bean.getClass().getPackage().getName();
        Set<ObjectName> objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        assertEquals(1, objectNames.size());

        // Removing the bean should unregister the MBean.
        container.removeBean(bean);
        objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        assertEquals(0, objectNames.size());
    }

    @Test
    public void testStoppingContainerDoesNotUnregistersMBeans() throws Exception
    {
        QueuedThreadPool bean = new QueuedThreadPool();
        container.addBean(bean, true);

        String pkg = bean.getClass().getPackage().getName();
        Set<ObjectName> objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        // QueuedThreadPool and ThreadPoolBudget.
        assertEquals(2, objectNames.size());

        container.stop();

        objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        assertEquals(2, objectNames.size());

        // Remove the MBeans to start clean on the next test.
        objectNames.forEach(objectName ->
        {
            try
            {
                mbeanServer.unregisterMBean(objectName);
            }
            catch (Throwable ignored)
            {
            }
        });
    }

    @Test
    public void testDestroyingContainerUnregistersMBeans() throws Exception
    {
        QueuedThreadPool bean = new QueuedThreadPool();
        container.addBean(bean, true);

        String pkg = bean.getClass().getPackage().getName();
        Set<ObjectName> objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        // QueuedThreadPool and ThreadPoolBudget.
        assertEquals(2, objectNames.size());

        container.stop();
        container.destroy();

        objectNames = mbeanServer.queryNames(ObjectName.getInstance(pkg + ":*"), null);
        assertEquals(0, objectNames.size());
    }
}
