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

import org.eclipse.jetty.server.session.AbstractForwardedSessionTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.InfinispanTestSessionServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * RemoteForwardedSessionTest
 *
 *
 */
public class RemoteForwardedSessionTest extends AbstractForwardedSessionTest
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


    @Override
    public AbstractTestServer createServer(int port, int maxInactiveMs, int scavenge, int evictionPolicy) throws Exception
    {
        return new InfinispanTestSessionServer(port, maxInactiveMs, scavenge, evictionPolicy, __testSupport.getCache());
    }

    @Test
    public void testSessionCreateInForward() throws Exception
    {
        super.testSessionCreateInForward();
    }

}
