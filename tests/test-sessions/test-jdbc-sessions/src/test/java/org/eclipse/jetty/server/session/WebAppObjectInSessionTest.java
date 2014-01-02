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

import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Test;

/**
 * WebAppObjectInSessionTest
 *
 *
 */
public class WebAppObjectInSessionTest extends AbstractWebAppObjectInSessionTest
{

    public AbstractTestServer createServer(int port)
    {
        Resource.setDefaultUseCaches(false);
        return new JdbcTestServer(port);
    }

    @Test
    public void testWebappObjectInSession() throws Exception
    {
        super.testWebappObjectInSession();
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
