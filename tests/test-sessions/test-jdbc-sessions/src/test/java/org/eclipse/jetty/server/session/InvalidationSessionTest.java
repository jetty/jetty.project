//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.After;
import org.junit.Test;

/**
 * InvalidationSessionTest
 */
public class InvalidationSessionTest extends AbstractInvalidationSessionTest
{
    public AbstractTestServer createServer(int port)
    {
        return new JdbcTestServer(port);
    }
    
    public void pause()
    {
        //This test moves around a session between 2 nodes. Due to optimizations in the handling of
        //the sessions for the JDBC SessionManager, this can mean that a session that may have been
        //deleted on one node is then accessed again shortly afterwards, it can appear as if the
        //session is still live in the memory of that node. By waiting a little time, we can ensure
        //that the node will re-load the session from the database and discover that it has gone.
        try
        {
            Thread.sleep(2 * JdbcTestServer.SAVE_INTERVAL * 1000);
        }
        catch (InterruptedException e)
        {
        }
    }

    @Test
    public void testInvalidation() throws Exception
    {
        super.testInvalidation();
    }  
    
    
    @After
    public void tearDown() throws Exception 
    {
        try
        {
            DriverManager.getConnection( "jdbc:derby:sessions;shutdown=true" );
        }
        catch( SQLException expected )
        {
        }
    }
}
