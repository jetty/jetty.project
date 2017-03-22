//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.gcloud.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Set;

import org.eclipse.jetty.server.session.AbstractNonClusteredSessionScavengingTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.After;
import org.junit.Test;

/**
 * NonClusteredSessionScavengingTest
 *
 *
 */
public class NonClusteredSessionScavengingTest extends AbstractNonClusteredSessionScavengingTest
{

    
    @After
    public void teardown () throws Exception
    {
        GCloudTestSuite.__testSupport.deleteSessions();
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractNonClusteredSessionScavengingTest#assertSession(java.lang.String, boolean)
     */
    @Override
    public void assertSession(String id, boolean exists)
    {
        try
        {
            Set<String> ids = GCloudTestSuite.__testSupport.getSessionIds();
            if (exists)
                assertTrue(ids.contains(id));
            else
                assertFalse(ids.contains(id));
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
        return GCloudSessionTestSupport.newSessionDataStoreFactory(GCloudTestSuite.__testSupport.getDatastore());
    }
    
}
