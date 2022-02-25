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
import javax.management.Attribute;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ObjectName;

import com.acme.Derived;
import com.acme.Managed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObjectMBeanTest
{
    private MBeanContainer container;

    @BeforeEach
    public void before()
    {
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    }

    @AfterEach
    public void after()
    {
        container.destroy();
        container = null;
    }

    @Test
    public void testMBeanForNull()
    {
        Object mBean = container.mbeanFor(null);
        assertNull(mBean);
    }

    @Test
    public void testMBeanForString()
    {
        String obj = "foo";
        Object mbean = container.mbeanFor(obj);
        assertNotNull(mbean);
        container.beanAdded(null, obj);
        ObjectName objectName = container.findMBean(obj);
        assertNotNull(objectName);
    }

    @Test
    public void testMBeanForStringArray()
    {
        String[] obj = {"a", "b"};
        Object mbean = container.mbeanFor(obj);
        assertNotNull(mbean);
        container.beanAdded(null, obj);
        ObjectName objectName = container.findMBean(obj);
        assertNotNull(objectName);
    }

    @Test
    public void testMBeanForIntArray()
    {
        int[] obj = {0, 1, 2};
        Object mbean = container.mbeanFor(obj);
        assertNotNull(mbean);
        container.beanAdded(null, obj);
        ObjectName objectName = container.findMBean(obj);
        assertNotNull(objectName);
    }

    @Test
    public void testMetaDataCaching()
    {
        Derived derived = new Derived();
        ObjectMBean derivedMBean = (ObjectMBean)container.mbeanFor(derived);
        ObjectMBean derivedMBean2 = (ObjectMBean)container.mbeanFor(derived);
        assertNotSame(derivedMBean, derivedMBean2);
        assertSame(derivedMBean.metaData(), derivedMBean2.metaData());
    }

    @Test
    public void testDerivedAttributes() throws Exception
    {
        Derived derived = new Derived();
        Managed managed = derived.getManagedInstance();
        ObjectMBean derivedMBean = (ObjectMBean)container.mbeanFor(derived);
        ObjectMBean managedMBean = (ObjectMBean)container.mbeanFor(managed);

        container.beanAdded(null, derived);
        container.beanAdded(null, managed);

        MBeanInfo derivedInfo = derivedMBean.getMBeanInfo();
        assertNotNull(derivedInfo);
        MBeanInfo managedInfo = managedMBean.getMBeanInfo();
        assertNotNull(managedInfo);

        assertEquals("com.acme.Derived", derivedInfo.getClassName(), "name does not match");
        assertEquals("Test the mbean stuff", derivedInfo.getDescription(), "description does not match");
        assertEquals(5, derivedInfo.getAttributes().length, "attribute count does not match");
        assertEquals("Full Name", derivedMBean.getAttribute("fname"), "attribute values does not match");

        derivedMBean.setAttribute(new Attribute("fname", "Fuller Name"));
        assertEquals("Fuller Name", derivedMBean.getAttribute("fname"), "set attribute value does not match");
        assertEquals("goop", derivedMBean.getAttribute("goop"), "proxy attribute values do not match");
    }

    @Test
    public void testDerivedOperations() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)container.mbeanFor(derived);

        container.beanAdded(null, derived);

        MBeanInfo info = mbean.getMBeanInfo();
        assertEquals(5, info.getOperations().length, "operation count does not match");

        MBeanOperationInfo[] operationInfos = info.getOperations();
        boolean publish = false;
        boolean doodle = false;
        boolean good = false;
        for (MBeanOperationInfo operationInfo : operationInfos)
        {
            if ("publish".equals(operationInfo.getName()))
            {
                publish = true;
                assertEquals("publish something", operationInfo.getDescription(), "description doesn't match");
            }

            if ("doodle".equals(operationInfo.getName()))
            {
                doodle = true;
                assertEquals("Doodle something", operationInfo.getDescription(), "description doesn't match");
                MBeanParameterInfo[] parameterInfos = operationInfo.getSignature();
                assertEquals("A description of the argument", parameterInfos[0].getDescription(), "parameter description doesn't match");
                assertEquals("doodle", parameterInfos[0].getName(), "parameter name doesn't match");
            }

            // This is a proxied operation on the MBean wrapper.
            if ("good".equals(operationInfo.getName()))
            {
                good = true;
                assertEquals("test of proxy operations", operationInfo.getDescription(), "description does not match");
                assertEquals("not bad", mbean.invoke("good", new Object[]{}, new String[]{}), "execution contexts wrong");
            }
        }

        assertTrue(publish, "publish operation was not not found");
        assertTrue(doodle, "doodle operation was not not found");
        assertTrue(good, "good operation was not not found");
    }

    @Test
    public void testMethodNameMining()
    {
        assertEquals("fullName", MetaData.toAttributeName("getFullName"));
        assertEquals("fullName", MetaData.toAttributeName("getfullName"));
        assertEquals("fullName", MetaData.toAttributeName("isFullName"));
        assertEquals("fullName", MetaData.toAttributeName("isfullName"));
        assertEquals("fullName", MetaData.toAttributeName("setFullName"));
        assertEquals("fullName", MetaData.toAttributeName("setfullName"));
        assertEquals("fullName", MetaData.toAttributeName("FullName"));
        assertEquals("fullName", MetaData.toAttributeName("fullName"));
    }
}
