//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.Derived;

import java.lang.management.ManagementFactory;

import javax.management.Attribute;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ObjectMBeanTest
{
    private static final Logger LOG = Log.getLogger(ObjectMBeanTest.class);

    private static MBeanContainer container;

    @BeforeEach
    public void before() throws Exception
    {
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    }

    @AfterEach
    public void after() throws Exception
    {
        container.destroy();
        container = null;
    }

    /*
     * this test uses the com.acme.Derived test classes
     */
    @Test
    public void testDerivedAttributes() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);

        ObjectMBean managed = (ObjectMBean)ObjectMBean.mbeanFor(derived.getManagedInstance());
        mbean.setMBeanContainer(container);
        managed.setMBeanContainer(container);

        container.beanAdded(null,derived);
        container.beanAdded(null,derived.getManagedInstance());

        MBeanInfo toss = managed.getMBeanInfo();

        assertNotNull(mbean.getMBeanInfo());

        MBeanInfo info = mbean.getMBeanInfo();

        assertEquals("com.acme.Derived", info.getClassName(), "name does not match");
        assertEquals("Test the mbean stuff", info.getDescription(), "description does not match");

        // for ( MBeanAttributeInfo i : info.getAttributes())
        // {
        // LOG.debug(i.toString());
        // }

        /*
         * 2 attributes from lifecycle and 2 from Derived and 1 from MBean
         */
        assertEquals(6, info.getAttributes().length, "attribute count does not match");

        assertEquals("Full Name", mbean.getAttribute("fname"), "attribute values does not match");

        mbean.setAttribute(new Attribute("fname","Fuller Name"));

        assertEquals("Fuller Name", mbean.getAttribute("fname"), "set attribute value does not match");

        assertEquals("goop", mbean.getAttribute("goop"), "proxy attribute values do not match");

        // Thread.sleep(100000);
    }

    @Test
    public void testDerivedOperations() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);

        mbean.setMBeanContainer(container);

        container.beanAdded(null,derived);

        MBeanInfo info = mbean.getMBeanInfo();

        assertEquals(5, info.getOperations().length, "operation count does not match");

        MBeanOperationInfo[] opinfos = info.getOperations();
        boolean publish = false;
        boolean doodle = false;
        boolean good = false;
        for (int i = 0; i < opinfos.length; ++i)
        {
            MBeanOperationInfo opinfo = opinfos[i];

            if ("publish".equals(opinfo.getName()))
            {
                publish = true;
                assertEquals("publish something", opinfo.getDescription(), "description doesn't match");
            }

            if ("doodle".equals(opinfo.getName()))
            {
                doodle = true;
                assertEquals("Doodle something", opinfo.getDescription(), "description doesn't match");

                MBeanParameterInfo[] pinfos = opinfo.getSignature();

                assertEquals("A description of the argument", pinfos[0].getDescription(), "parameter description doesn't match");
                assertEquals("doodle", pinfos[0].getName(), "parameter name doesn't match");
            }

            // This is a proxied operation on the JMX wrapper
            if ("good".equals(opinfo.getName()))
            {
                good = true;

                assertEquals("test of proxy operations", opinfo.getDescription(), "description does not match");
                assertEquals("not bad",mbean.invoke("good",new Object[] {}, new String[] {}), "execution contexts wrong");
            }
        }

        assertTrue(publish, "publish operation was not not found");
        assertTrue(doodle, "doodle operation was not not found");
        assertTrue(good, "good operation was not not found");

    }

    @Test
    public void testDerivedObjectAttributes() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);

        ObjectMBean managed = (ObjectMBean)ObjectMBean.mbeanFor(derived.getManagedInstance());
        mbean.setMBeanContainer(container);
        managed.setMBeanContainer(container);

        assertNotNull(mbean.getMBeanInfo());

        container.beanAdded(null,derived);
        container.beanAdded(null,derived.getManagedInstance());
        container.beanAdded(null,mbean);
        container.beanAdded(null,managed);

        // Managed managedInstance = (Managed)mbean.getAttribute("managedInstance");
        // assertNotNull(managedInstance);
        // assertEquals("foo", managedInstance.getManaged(), "managed instance returning nonsense");

    }

    @Test
    @Disabled("ignore, used in testing jconsole atm")
    public void testThreadPool() throws Exception
    {

        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);

        ObjectMBean managed = (ObjectMBean)ObjectMBean.mbeanFor(derived.getManagedInstance());
        mbean.setMBeanContainer(container);
        managed.setMBeanContainer(container);

        QueuedThreadPool qtp = new QueuedThreadPool();

        ObjectMBean bqtp = (ObjectMBean)ObjectMBean.mbeanFor(qtp);

        bqtp.getMBeanInfo();

        container.beanAdded(null,derived);
        container.beanAdded(null,derived.getManagedInstance());
        container.beanAdded(null,mbean);
        container.beanAdded(null,managed);
        container.beanAdded(null,qtp);

        Thread.sleep(10000000);

    }

    @Test
    public void testMethodNameMining() throws Exception
    {
        ObjectMBean mbean = new ObjectMBean(new Derived());

        assertEquals("fullName",mbean.toVariableName("getFullName"));
        assertEquals("fullName",mbean.toVariableName("getfullName"));
        assertEquals("fullName",mbean.toVariableName("isFullName"));
        assertEquals("fullName",mbean.toVariableName("isfullName"));
        assertEquals("fullName",mbean.toVariableName("setFullName"));
        assertEquals("fullName",mbean.toVariableName("setfullName"));
        assertEquals("fullName",mbean.toVariableName("FullName"));
        assertEquals("fullName",mbean.toVariableName("fullName"));
    }

}
