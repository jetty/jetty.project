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


package org.eclipse.jetty.server.session.remote;

import org.eclipse.jetty.server.session.AbstractLocalSessionScavengingTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.InfinispanTestSessionServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * LocalSessionScavengingTest
 *
 *
 */
public class RemoteLocalSessionScavengingTest extends AbstractLocalSessionScavengingTest
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
     * @see org.eclipse.jetty.server.session.AbstractLocalSessionScavengingTest#createServer(int, int, int, int)
     */
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
        return new InfinispanTestSessionServer(port, max, scavenge, evictionPolicy, __testSupport.getCache());
    }

}
