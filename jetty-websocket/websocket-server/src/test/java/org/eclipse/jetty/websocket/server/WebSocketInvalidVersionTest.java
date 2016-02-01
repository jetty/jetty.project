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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.HttpResponse;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

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
     * @throws Exception on test failure
     */
    @Test
    public void testRequestVersion29() throws Exception
    {
        @SuppressWarnings("resource")
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setVersion(29); // intentionally bad version
        try
        {
            client.connect();
            client.sendStandardRequest();
            HttpResponse response = client.readResponseHeader();
            Assert.assertThat("Response Status Code",response.getStatusCode(),is(400));
            Assert.assertThat("Response Status Reason",response.getStatusReason(),containsString("Unsupported websocket version specification"));
            Assert.assertThat("Response Versions",response.getHeader("Sec-WebSocket-Version"),is("13"));
        }
        finally
        {
            client.disconnect();
        }
    }
}
