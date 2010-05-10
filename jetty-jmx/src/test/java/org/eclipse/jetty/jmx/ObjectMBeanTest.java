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

import com.acme.Derived;
import org.eclipse.jetty.server.Server;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ObjectMBeanTest
{
    @Test
    public void testMbeanInfo()
    {
        Derived derived = new Derived();
        ObjectMBean mbean = new ObjectMBean(derived);
        assertTrue(mbean.getMBeanInfo()!=null); // TODO do more than just run it
    }

    @Test
    public void testMbeanFor()
    {
        Derived derived = new Derived();
        assertTrue(ObjectMBean.mbeanFor(derived)!=null); // TODO do more than just run it
        Server server = new Server();
        assertTrue(ObjectMBean.mbeanFor(server)!=null); // TODO do more than just run it
    }
}
