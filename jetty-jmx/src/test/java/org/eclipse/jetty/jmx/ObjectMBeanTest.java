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

import java.lang.management.ManagementFactory;

import javax.management.Attribute;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.acme.Derived;

public class ObjectMBeanTest
{
    private static final Logger LOG = Log.getLogger(ObjectMBeanTest.class);

    private static MBeanContainer container;

    @Before
    public void before() throws Exception
    {
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
    }

    @After
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

        Assert.assertNotNull(mbean.getMBeanInfo());

        MBeanInfo info = mbean.getMBeanInfo();

        Assert.assertEquals("name does not match","com.acme.Derived",info.getClassName());
        Assert.assertEquals("description does not match","Test the mbean stuff",info.getDescription());

        // for ( MBeanAttributeInfo i : info.getAttributes())
        // {
        // LOG.debug(i.toString());
        // }

        /*
         * 2 attributes from lifecycle and 2 from Derived and 1 from MBean
         */
        Assert.assertEquals("attribute count does not match",6,info.getAttributes().length);

        Assert.assertEquals("attribute values does not match","Full Name",mbean.getAttribute("fname"));

        mbean.setAttribute(new Attribute("fname","Fuller Name"));

        Assert.assertEquals("set attribute value does not match","Fuller Name",mbean.getAttribute("fname"));

        Assert.assertEquals("proxy attribute values do not match","goop",mbean.getAttribute("goop"));

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

        Assert.assertEquals("operation count does not match",5,info.getOperations().length);

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
                Assert.assertEquals("description doesn't match","publish something",opinfo.getDescription());
            }

            if ("doodle".equals(opinfo.getName()))
            {
                doodle = true;
                Assert.assertEquals("description doesn't match","Doodle something",opinfo.getDescription());

                MBeanParameterInfo[] pinfos = opinfo.getSignature();

                Assert.assertEquals("parameter description doesn't match","A description of the argument",pinfos[0].getDescription());
                Assert.assertEquals("parameter name doesn't match","doodle",pinfos[0].getName());
            }

            // This is a proxied operation on the JMX wrapper
            if ("good".equals(opinfo.getName()))
            {
                good = true;

                Assert.assertEquals("description does not match","test of proxy operations",opinfo.getDescription());
                Assert.assertEquals("execution contexts wrong","not bad",mbean.invoke("good",new Object[] {},new String[] {}));
            }
        }

        Assert.assertTrue("publish operation was not not found",publish);
        Assert.assertTrue("doodle operation was not not found",doodle);
        Assert.assertTrue("good operation was not not found",good);

    }

    @Test
    public void testDerivedObjectAttributes() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);

        ObjectMBean managed = (ObjectMBean)ObjectMBean.mbeanFor(derived.getManagedInstance());
        mbean.setMBeanContainer(container);
        managed.setMBeanContainer(container);

        Assert.assertNotNull(mbean.getMBeanInfo());

        container.beanAdded(null,derived);
        container.beanAdded(null,derived.getManagedInstance());
        container.beanAdded(null,mbean);
        container.beanAdded(null,managed);

        // Managed managedInstance = (Managed)mbean.getAttribute("managedInstance");
        // Assert.assertNotNull(managedInstance);
        // Assert.assertEquals("managed instance returning nonsense", "foo", managedInstance.getManaged());

    }

    @Test
    @Ignore("ignore, used in testing jconsole atm")
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

        Assert.assertEquals("fullName",mbean.toVariableName("getFullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("getfullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("isFullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("isfullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("setFullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("setfullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("FullName"));
        Assert.assertEquals("fullName",mbean.toVariableName("fullName"));
    }

}
