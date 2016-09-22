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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Test;

public class SessionRenewTest extends AbstractSessionRenewTest
{

    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
        return new JdbcTestServer(port, max, scavenge, evictionPolicy);
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionRenewTest#verifyChange(WebAppContext, java.lang.String, java.lang.String)
     */
    @Override
    public boolean verifyChange(WebAppContext context, String oldSessionId, String newSessionId)
    {
        try
        {
            //assert the new one exists
            assertTrue(((JdbcTestServer)_server).existsInSessionTable(newSessionId, false));
            assertFalse(((JdbcTestServer)_server).existsInSessionTable(oldSessionId, false));
            return true;
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }
        
        return false;
    }



    @Test
    public void testSessionRenewal() throws Exception
    {
        super.testSessionRenewal();
    }
    
    
    @After
    public void tearDown() throws Exception 
    {
        JdbcTestServer.shutdown(null);
    }
    
}
