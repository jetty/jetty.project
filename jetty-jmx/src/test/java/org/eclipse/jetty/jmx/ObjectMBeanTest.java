// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.jmx;

import java.lang.management.ManagementFactory;

import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import junit.framework.Assert;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.acme.Derived;
import com.acme.Managed;


public class ObjectMBeanTest
{
    private static final Logger LOG = Log.getLogger(ObjectMBeanTest.class);

    private static MBeanContainer container;
    
    @BeforeClass
    public static void beforeClass() throws Exception
    {
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        container.start();
    }
    
    @AfterClass
    public static void afterClass() throws Exception
    {
        container.stop();
        container = null;
    }
    
    /*
     * this test uses the com.acme.Derived test classes
     */
    @Test
    public void testMbeanInfo() throws Exception
    {
        
        Derived derived = new Derived();
        ObjectMBean mbean = (ObjectMBean)ObjectMBean.mbeanFor(derived);
        
        ObjectMBean managed = (ObjectMBean)ObjectMBean.mbeanFor(derived.getManagedInstance());
        mbean.setMBeanContainer(container);
        managed.setMBeanContainer(container);
                
        container.addBean(mbean);
        container.addBean(managed);                
        
        MBeanInfo toss = managed.getMBeanInfo();
        
        Assert.assertNotNull(mbean.getMBeanInfo());
        
        MBeanInfo info = mbean.getMBeanInfo();
        
        Assert.assertEquals("name does not match", "com.acme.Derived", info.getClassName());
        Assert.assertEquals("description does not match", "Test the mbean stuff", info.getDescription());

        for ( MBeanAttributeInfo i : info.getAttributes())
        {
            LOG.debug(i.toString());
        }
        
        /*
         * 6 attributes from lifecycle and 2 from Derived and 1 from MBean
         */
        Assert.assertEquals("attribute count does not match", 9, info.getAttributes().length);

        Assert.assertEquals("attribute values does not match", "Full Name", mbean.getAttribute("fname") );
        
        mbean.setAttribute( new Attribute("fname","Fuller Name"));
        
        Assert.assertEquals("set attribute value does not match", "Fuller Name", mbean.getAttribute("fname") );
        
        Assert.assertEquals("proxy attribute values do not match", "goop", mbean.getAttribute("goop") );
        
        Assert.assertEquals("operation count does not match", 5, info.getOperations().length);
        
        MBeanOperationInfo[] opinfos = info.getOperations();
        boolean publish = false;
        boolean doodle = false;
        boolean good = false;
        for ( int i = 0 ; i < opinfos.length; ++i )
        {
            MBeanOperationInfo opinfo = opinfos[i];
            
            LOG.debug(opinfo.getName());
            
            if ("publish".equals(opinfo.getName()))
            {
                publish = true;
                Assert.assertEquals("description doesn't match", "publish something", opinfo.getDescription());
            }
            
            if ("doodle".equals(opinfo.getName()))
            {
                doodle = true;
                Assert.assertEquals("description doesn't match", "Doodle something", opinfo.getDescription());
                
                MBeanParameterInfo[] pinfos = opinfo.getSignature();
                
                Assert.assertEquals("parameter description doesn't match", "A description of the argument", pinfos[0].getDescription());
                Assert.assertEquals("parameter name doesn't match", "doodle", pinfos[0].getName());
            }
            
            if ("good".equals(opinfo.getName()))
            {
                good = true;
                
                Assert.assertEquals("description does not match", "test of proxy operations", opinfo.getDescription());               
                Assert.assertEquals("execution contexts wrong", "not bad", mbean.invoke("good", new Object[] {}, new String[] {}));             
            }
        }
        
        Assert.assertTrue("publish operation was not not found", publish);
        Assert.assertTrue("doodle operation was not not found", doodle);
        Assert.assertTrue("good operation was not not found", good);

        // TODO sort out why this is not working...something off in Bean vs MBean ism's
        
        Managed managedInstance = (Managed)mbean.getAttribute("managedInstance");
        Assert.assertNotNull(managedInstance);
        Assert.assertEquals("managed instance returning nonsense", "foo", managedInstance.getManaged());
    }
}
