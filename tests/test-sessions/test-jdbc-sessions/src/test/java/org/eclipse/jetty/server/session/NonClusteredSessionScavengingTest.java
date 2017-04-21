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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;



import org.junit.After;
import org.junit.Test;


/**
 * NonClusteredSessionScavengingTest
 */
public class NonClusteredSessionScavengingTest extends AbstractNonClusteredSessionScavengingTest
{
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
       return JdbcTestHelper.newSessionDataStoreFactory();
    }

    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractNonClusteredSessionScavengingTest#assertSession(java.lang.String, boolean)
     */
    @Override
    public void assertSession(String id, boolean exists)
    {
        try
        {
            boolean inDb = JdbcTestHelper.existsInSessionTable(id, false);
            if (exists)
                assertTrue(inDb);
            else
                assertFalse(inDb);
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }
    }


    @After
    public void tearDown() throws Exception 
    {
        JdbcTestHelper.shutdown(null);
    }
  
}
