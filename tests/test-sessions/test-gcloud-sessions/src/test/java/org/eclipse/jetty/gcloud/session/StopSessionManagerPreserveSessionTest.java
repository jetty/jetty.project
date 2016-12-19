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


package org.eclipse.jetty.gcloud.session;

import static org.junit.Assert.fail;
import org.eclipse.jetty.server.session.AbstractStopSessionManagerPreserveSessionTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * StopSessionManagerPreserveSessionTest
 *
 *
 */
public class StopSessionManagerPreserveSessionTest extends AbstractStopSessionManagerPreserveSessionTest
{

    @AfterClass
    public static void teardown () throws Exception
    {
        GCloudTestSuite.__testSupport.deleteSessions();
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractStopSessionManagerPreserveSessionTest#checkSessionPersisted(String, boolean)
     */
    @Override
    public void checkSessionPersisted(String id, boolean expected)
    {
        try
        {
            GCloudTestSuite.__testSupport.assertSessions(1);
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }

    }


    @Override
    public AbstractTestServer createServer(int port, int maxInactiveMs, int scavengeMs,int evictionPolicy) throws Exception
    {
       return new GCloudTestServer(port, maxInactiveMs, scavengeMs, evictionPolicy);
    }
    /** 
     * @see org.eclipse.jetty.server.session.AbstractStopSessionManagerPreserveSessionTest#configureSessionManagement(org.eclipse.jetty.servlet.ServletContextHandler)
     */
    @Override
    public void configureSessionManagement(ServletContextHandler context)
    {
        
    }

    @Test
    @Override
    public void testStopSessionManagerPreserveSession() throws Exception
    {
        super.testStopSessionManagerPreserveSession();
    }

    
}
