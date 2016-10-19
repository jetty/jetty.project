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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionExpiryTest extends AbstractSessionExpiryTest
{

    public static InfinispanTestSupport __testSupport;
    
    @BeforeClass
    public static void setup () throws Exception
    {
        __testSupport = new InfinispanTestSupport();
        __testSupport.setup();
    }
    
    @AfterClass
    public static void teardown () throws Exception
    {
        __testSupport.teardown();
    }
    
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
       InfinispanTestSessionServer server =  new InfinispanTestSessionServer(port, max, scavenge, evictionPolicy, __testSupport.getCache());
       return server;
    }

    @Test
    @Override
    public void testSessionNotExpired() throws Exception
    {
        super.testSessionNotExpired();
    }

    @Test
    @Override
    public void testSessionExpiry() throws Exception
    {
       super.testSessionExpiry();
    }
    
    @Override
    public void verifySessionDestroyed (TestHttpSessionListener listener, String sessionId)
    {
        //noop - sessions that expired when the InfinispanSessionManager was not running are not reloaded and do not have their listeners called on them.
    }
}
