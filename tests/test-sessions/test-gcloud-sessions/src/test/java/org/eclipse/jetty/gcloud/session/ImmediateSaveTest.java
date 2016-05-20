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

import org.eclipse.jetty.server.session.AbstractImmediateSaveTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.SessionHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;



/**
 * ImmediateSaveTest
 *
 *
 */
public class ImmediateSaveTest extends AbstractImmediateSaveTest
{
   
    static GCloudSessionTestSupport _testSupport;

    @BeforeClass
    public static void setup () throws Exception
    {
        _testSupport = new GCloudSessionTestSupport();
        _testSupport.setUp();
    }
    
    
    @After
    public void deleteSessions () throws Exception
    {
        _testSupport.deleteSessions();
    }
    
    
    @AfterClass
    public static void teardown () throws Exception
    {
        _testSupport.tearDown();
    }


    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy)
    {        
        return new GCloudTestServer(port, max, scavenge, evictionPolicy,_testSupport.getConfiguration()) 
        {
            public SessionHandler newSessionHandler()
            {
                SessionHandler h = super.newSessionHandler();
                h.getSessionCache().setSaveOnCreate(true);
                return h;
            }
        };
    }
}
