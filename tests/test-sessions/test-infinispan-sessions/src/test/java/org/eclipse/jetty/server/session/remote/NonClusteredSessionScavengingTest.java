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


package org.eclipse.jetty.server.session.remote;


import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;



import org.eclipse.jetty.server.session.AbstractNonClusteredSessionScavengingTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * NonClusteredSessionScavengingTest
 *
 *
 */
public class NonClusteredSessionScavengingTest extends AbstractNonClusteredSessionScavengingTest
{

   public static RemoteInfinispanTestSupport __testSupport;
    
    
    @BeforeClass
    public static void setup () throws Exception
    {
        __testSupport = new RemoteInfinispanTestSupport("remote-session-test");
        __testSupport.setup();
    }
    
    @AfterClass
    public static void teardown () throws Exception
    {
        __testSupport.teardown();
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractNonClusteredSessionScavengingTest#assertSession(java.lang.String, boolean)
     */
    @Override
    public void assertSession(String id, boolean exists)
    {
        assertNotNull(_dataStore);
        
        try
        {
            boolean inmap = _dataStore.exists(id);
            if (exists)
                assertTrue(inmap);
            else
                assertFalse(inmap);
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }
    }
    
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(__testSupport.getCache());
        return factory;
    }
}
