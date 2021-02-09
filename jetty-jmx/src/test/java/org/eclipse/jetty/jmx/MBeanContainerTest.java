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

package org.eclipse.jetty.jmx;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.acme.Managed;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MBeanContainerTest
{
    private MBeanContainer mbeanContainer;
    private MBeanServer mbeanServer;
    private String beanName;
    private Managed managed;
    private ObjectName objectName;

    @BeforeEach
    public void setUp()
    {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        mbeanContainer = new MBeanContainer(mbeanServer);
    }

    @Test
    public void testMakeName()
    {
        beanName = "mngd:bean";

        beanName = mbeanContainer.makeName(beanName);

        assertEquals("mngd_bean", beanName, "Bean name should be mngd_bean");
    }

    @Test
    public void testFindBean()
    {
        managed = getManaged();

        objectName = mbeanContainer.findMBean(managed);
        assertNotNull(objectName);

        assertEquals(managed, mbeanContainer.findBean(objectName), "Bean must be added");
        assertNull(mbeanContainer.findBean(null), "It must return null as there is no bean with the name null");
    }

    private Managed getManaged()
    {
        beanName = "mngd:bean";
        beanName = mbeanContainer.makeName(beanName);
        Managed managed = new Managed();
        managed.setManaged(beanName);
        mbeanContainer.beanAdded(null, managed);
        return managed;
    }

    @Test
    public void testMBeanContainer()
    {
        assertNotNull(mbeanContainer, "Container shouldn't be null");
    }

    @Test
    public void testGetMBeanServer()
    {
        assertEquals(mbeanServer, mbeanContainer.getMBeanServer(), "MBean server Instance must be equal");
    }

    @Test
    public void testDomain()
    {
        String domain = "Test";

        mbeanContainer.setDomain(domain);

        assertEquals(domain, mbeanContainer.getDomain(), "Domain name must be Test");
    }

    @Test
    public void testBeanAdded()
    {
        setBeanAdded();

        objectName = mbeanContainer.findMBean(managed);

        assertTrue(mbeanServer.isRegistered(objectName), "Bean must have been registered");
    }

    @Test
    public void testBeanAddedNullCheck()
    {
        setBeanAdded();
        Integer mbeanCount = mbeanServer.getMBeanCount();

        mbeanContainer.beanAdded(null, null);

        assertEquals(mbeanCount, mbeanServer.getMBeanCount(), "MBean count must not change after beanAdded(null, null) call");
    }

    private void setBeanAdded()
    {
        managed = new Managed();
        Container container = new ContainerLifeCycle();
        mbeanContainer.beanAdded(container, managed);
        mbeanServer = mbeanContainer.getMBeanServer();
    }

    @Test
    public void testBeanRemoved()
    {
        setUpBeanRemoved();

        mbeanContainer.beanRemoved(null, managed);

        assertNull(mbeanContainer.findMBean(managed), "Bean shouldn't be registered with container as we removed the bean");
    }

    private void setUpBeanRemoved()
    {
        managed = new Managed();
        mbeanContainer.beanRemoved(null, managed);
        mbeanContainer.beanAdded(null, managed);
    }

    @Test
    public void testBeanRemovedInstanceNotFoundException() throws Exception
    {
        // given
        setUpBeanRemoved();
        objectName = mbeanContainer.findMBean(managed);

        // when
        mbeanContainer.getMBeanServer().unregisterMBean(objectName);

        // then
        assertFalse(mbeanServer.isRegistered(objectName), "Bean must not have been registered as we unregistered the bean");
        // this flow covers InstanceNotFoundException. Actual code just eating
        // the exception. i.e Actual code just printing the stacktrace, whenever
        // an exception of type InstanceNotFoundException occurs.
        mbeanContainer.beanRemoved(null, managed);
    }

    @Test
    public void testDump()
    {
        assertNotNull(mbeanContainer.dump(), "Dump operation shouldn't return null if operation is success");
    }

    private void setUpDestroy()
    {
        managed = new Managed();
        mbeanContainer.beanAdded(null, managed);
    }

    @Test
    public void testDestroy()
    {
        setUpDestroy();

        objectName = mbeanContainer.findMBean(managed);
        mbeanContainer.destroy();

        assertFalse(mbeanContainer.getMBeanServer().isRegistered(objectName), "Unregistered bean - managed");
    }

    @Test
    public void testDestroyInstanceNotFoundException() throws Exception
    {
        setUpDestroy();

        objectName = mbeanContainer.findMBean(managed);
        mbeanContainer.getMBeanServer().unregisterMBean(objectName);

        assertFalse(mbeanContainer.getMBeanServer().isRegistered(objectName), "Unregistered bean - managed");
        // this flow covers InstanceNotFoundException. Actual code just eating
        // the exception. i.e Actual code just printing the stacktrace, whenever
        // an exception of type InstanceNotFoundException occurs.
        mbeanContainer.destroy();
    }

    @Test
    public void testNonManagedLifecycleNotUnregistered() throws Exception
    {
        testNonManagedObjectNotUnregistered(new ContainerLifeCycle());
    }

    @Test
    public void testNonManagedPojoNotUnregistered() throws Exception
    {
        testNonManagedObjectNotUnregistered(new Object());
    }

    private void testNonManagedObjectNotUnregistered(Object lifeCycle) throws Exception
    {
        ContainerLifeCycle parent = new ContainerLifeCycle();
        parent.addBean(mbeanContainer);

        ContainerLifeCycle child = new ContainerLifeCycle();
        parent.addBean(child);

        parent.addBean(lifeCycle, true);
        child.addBean(lifeCycle, false);

        parent.start();

        parent.removeBean(child);

        assertNotNull(mbeanContainer.findMBean(lifeCycle));
    }
}
