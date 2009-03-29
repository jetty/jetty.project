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


import junit.framework.TestCase;

import org.eclipse.jetty.server.Server;

import com.acme.Derived;

public class ObjectMBeanTest extends TestCase
{
    public ObjectMBeanTest(String arg0)
    {
        super(arg0);
    }

    public static void main(String[] args)
    {
        junit.textui.TestRunner.run(ObjectMBeanTest.class);
    }

    protected void setUp() throws Exception
    {
        super.setUp();
    }

    protected void tearDown() throws Exception
    {
        super.tearDown();
    }
    
    public void testMbeanInfo()
    {
        Derived derived = new Derived();
        ObjectMBean mbean = new ObjectMBean(derived);
        assertTrue(mbean.getMBeanInfo()!=null); // TODO do more than just run it
    }
    
    public void testMbeanFor()
    {
        Derived derived = new Derived();
        assertTrue(ObjectMBean.mbeanFor(derived)!=null); // TODO do more than just run it
        Server server = new Server();
        assertTrue(ObjectMBean.mbeanFor(server)!=null); // TODO do more than just run it
    }
}
