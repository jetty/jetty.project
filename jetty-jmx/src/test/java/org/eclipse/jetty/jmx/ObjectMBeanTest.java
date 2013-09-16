//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.acme.Derived;

public class ObjectMBeanTest
{
    @Test
    public void testMbeanInfo()
    {
        Derived derived = new Derived();
        ObjectMBean mbean = new ObjectMBean(derived);
        assertTrue(mbean.getMBeanInfo()!=null); // TODO do more than just run it
    }
}
