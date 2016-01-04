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

package org.eclipse.jetty.server;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.junit.Before;
import org.junit.runner.RunWith;

/**
 * HttpServer Tester.
 */
@RunWith(AdvancedRunner.class)
public class ServerConnectorHttpServerTest extends HttpServerTestBase
{
    @Before
    public void init() throws Exception
    {
        // Run this test with 0 acceptors. Other tests already check the acceptors >0
        startServer(new ServerConnector(_server,0,1));
    }
}
