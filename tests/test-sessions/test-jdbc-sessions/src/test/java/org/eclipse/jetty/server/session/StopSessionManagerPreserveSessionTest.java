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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Test;

public class StopSessionManagerPreserveSessionTest extends AbstractStopSessionManagerPreserveSessionTest
{
    JdbcTestServer _server;
    
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
    
    @Override
    public void checkSessionPersisted(boolean expected)
    {
        try
        {
            boolean actual = _server.existsInSessionTable(_id, true);
            System.err.println(expected+":"+actual);
            assertEquals(expected, actual);
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }
    }

    @Override
    public AbstractTestServer createServer(int port)
    {
        _server = new JdbcTestServer(0);
        return _server;
    }

    @Override
    public void configureSessionManagement(ServletContextHandler context)
    {
      //nothing special
    }

    
    @Test
    public void testStopSessionManagerPreserveSession() throws Exception
    {
        super.testStopSessionManagerPreserveSession();
    }
}
