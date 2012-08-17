//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.junit.Before;

/**
 * HttpServer Tester.
 */
public class SelectChannelServerTest extends HttpServerTestBase
{
    @Before
    public void init() throws Exception
    {
        startServer(new SelectChannelConnector(_server));
    }

    @Override
    public void testRequest1() throws Exception
    {
        super.testRequest1();
    }

    @Override
    public void testSuspendedPipeline() throws Exception
    {
        super.testSuspendedPipeline();
    }
    
    @Override
    public void testUnreadInput() throws Exception
    {
        super.testUnreadInput();
    }


}
