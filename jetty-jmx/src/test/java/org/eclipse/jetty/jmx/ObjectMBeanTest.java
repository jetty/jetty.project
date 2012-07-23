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

import static org.junit.Assert.assertTrue;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;

import junit.framework.Assert;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Test;

import com.acme.Derived;


public class ObjectMBeanTest
{
    private static final Logger LOG = Log.getLogger(ObjectMBeanTest.class);

    /*
     * this test uses the com.acme.Derived test classes
     */
    @Test
    public void testMbeanInfo() throws Exception
    {
        Derived derived = new Derived();
        ObjectMBean mbean = new ObjectMBean(derived);
        assertTrue(mbean.getMBeanInfo()!=null);
        
        MBeanInfo info = mbean.getMBeanInfo();
        
        Assert.assertEquals("name does not match", "com.acme.Derived", info.getClassName());
        Assert.assertEquals("description does not match", "Test the mbean stuff", info.getDescription());

        for ( MBeanAttributeInfo i : info.getAttributes())
        {
            LOG.debug(i.toString());
        }
        
        /*
         * 6 attributes from lifecycle and 1 from Derived
         */
        Assert.assertEquals("attribute count does not match", 7, info.getAttributes().length);

        Assert.assertEquals("attribute values does not match", "Full Name", mbean.getAttribute("fname") );
        
        Assert.assertEquals("operation count does not match", 4, info.getOperations().length);
        
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
                doodle = true;
                
                Assert.assertEquals("description does not match", "test of proxy", opinfo.getDescription());
                Assert.assertEquals("execution contexts wrong", "not bad", mbean.invoke("good", new Object[] {}, new String[] {}));
                
            }
        }
        
        Assert.assertTrue("publish operation was not not found", publish);
        Assert.assertTrue("doodle operation was not not found", doodle);
       // Assert.assertTrue("good operation was not not found", good); not wired up yet


    }
}
