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

package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

public class WebSocketInvalidVersionTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new MyEchoServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test the requirement of responding with an http 400 when using a Sec-WebSocket-Version that is unsupported.
     */
    @Test
    public void testRequestVersion29() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setVersion(29); // intentionally bad version
        try
        {
            client.connect();
            client.sendStandardRequest();
            String respHeader = client.readResponseHeader();
            Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 400 Unsupported websocket version specification"));
            Assert.assertThat("Response Header Versions",respHeader,containsString("Sec-WebSocket-Version: 13, 0\r\n"));
        }
        finally
        {
            client.close();
        }
    }
}
