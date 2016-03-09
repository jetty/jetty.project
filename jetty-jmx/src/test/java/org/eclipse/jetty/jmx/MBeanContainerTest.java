//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import com.acme.Managed;

public class MBeanContainerTest
{

    private MBeanContainer mbeanContainer;

    private MBeanServer mbeanServer;

    private String domain;

    private String beanName;

    private Managed managed;

    private ObjectName objectName;

    private Integer mbeanCount;

    @Before
    public void setUp()
    {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        mbeanContainer = new MBeanContainer(mbeanServer);
    }

    @Test
    public void testMakeName()
    {
        // given
        beanName = "mngd:bean";

        // when
        beanName = mbeanContainer.makeName(beanName);

        // then
        assertEquals("Bean name should be mngd_bean","mngd_bean",beanName);
    }

    @Test
    public void testFindBean()
    {
        // given
        managed = getManaged();

        // when
        objectName = mbeanContainer.findMBean(managed);
        assertNotNull(objectName);

        // then
        assertEquals("Bean must be added",managed,mbeanContainer.findBean(objectName));
        assertNull("It must return null as there is no bean with the name null",mbeanContainer.findBean(null));
    }

    private Managed getManaged()
    {
        beanName = "mngd:bean";
        beanName = mbeanContainer.makeName(beanName);
        Managed managed = new Managed();
        managed.setManaged(beanName);
        mbeanContainer.beanAdded(null,managed);
        return managed;
    }

    @Test
    public void testMBeanContainer()
    {
        assertNotNull("Container shouldn't be null",mbeanContainer);
    }

    @Test
    public void testGetMBeanServer()
    {
        assertEquals("MBean server Instance must be equal",mbeanServer,mbeanContainer.getMBeanServer());
    }

    @Test
    public void testDomain()
    {
        // given
        domain = "Test";

        // when
        mbeanContainer.setDomain(domain);

        // then
        assertEquals("Domain name must be Test",domain,mbeanContainer.getDomain());
    }

    @Test
    public void testBeanAdded() throws Exception
    {
        // given
        setBeanAdded();

        // when
        objectName = mbeanContainer.findMBean(managed);

        // then
        assertTrue("Bean must have been registered",mbeanServer.isRegistered(objectName));
    }

    @Test
    public void testBeanAddedNullCheck() throws Exception
    {
        // given
        setBeanAdded();
        mbeanCount = mbeanServer.getMBeanCount();

        // when
        mbeanContainer.beanAdded(null,null);

        // then
        assertEquals("Mbean count must not change after beanAdded(null, null) call",mbeanCount,mbeanServer.getMBeanCount());
    }

    private void setBeanAdded()
    {
        managed = new Managed();
        Container container = new ContainerLifeCycle();
        mbeanContainer.beanAdded(container,managed);
        mbeanServer = mbeanContainer.getMBeanServer();
    }

    @Test
    public void testBeanRemoved() throws Exception
    {
        // given
        setUpBeanRemoved();

        // when
        mbeanContainer.beanRemoved(null,managed);

        // then
        assertNull("Bean shouldn't be registed with container as we removed the bean",mbeanContainer.findMBean(managed));
    }

    private void setUpBeanRemoved()
    {
        managed = new Managed();
        mbeanContainer.beanRemoved(null,managed);
        mbeanContainer.beanAdded(null,managed);
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
        assertFalse("Bean must not have been registered as we unregisted the bean",mbeanServer.isRegistered(objectName));
        // this flow covers InstanceNotFoundException. Actual code just eating
        // the exception. i.e Actual code just printing the stacktrace, whenever
        // an exception of type InstanceNotFoundException occurs.
        mbeanContainer.beanRemoved(null,managed);
    }

    @Test
    public void testDump()
    {
        assertNotNull("Dump operation shouldn't return null if operation is success",mbeanContainer.dump());
    }

    private void setUpDestoy()
    {
        managed = new Managed();
        mbeanContainer.beanAdded(null,managed);
    }

    @Test
    public void testDestroy() throws Exception
    {
        // given
        setUpDestoy();

        // when
        mbeanContainer.destroy();
        objectName = mbeanContainer.findMBean(managed);

        // then
        assertFalse("Unregisted bean-  managed",mbeanContainer.getMBeanServer().isRegistered(objectName));
    }

    @Test
    public void testDestroyInstanceNotFoundException() throws Exception
    {
        // given
        setUpDestoy();
        objectName = mbeanContainer.findMBean(managed);

        // when
        mbeanContainer.getMBeanServer().unregisterMBean(objectName);

        // then
        assertFalse("Unregisted bean-  managed",mbeanContainer.getMBeanServer().isRegistered(objectName));
        // this flow covers InstanceNotFoundException. Actual code just eating
        // the exception. i.e Actual code just printing the stacktrace, whenever
        // an exception of type InstanceNotFoundException occurs.
        mbeanContainer.destroy();
    }
}
