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

package org.eclipse.jetty.http.spi;

import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.powermock.reflect.Whitebox;

public class JettyHttpServerBase
{

    protected JettyHttpServer jettyHttpServer;

    @BeforeClass
    public static void setUpBeforeClass()
    {
        Log.getRootLogger().setDebugEnabled(true);
    }

    @Before
    public void setUp() throws Exception
    {
        jettyHttpServer = new JettyHttpServer(new Server(),false);
    }

    @After
    public void tearDown() throws Exception
    {
        if (jettyHttpServer != null)
        {
            Server server = Whitebox.getInternalState(jettyHttpServer,"_server");
            if (server.getBeans(NetworkConnector.class) != null)
            {
                jettyHttpServer.stop(SpiConstants.DELAY);
            }
        }
    }
}
