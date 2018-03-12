//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import static org.hamcrest.Matchers.instanceOf;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class WebSocketInvalidVersionTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private static BlockheadClient client;
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

    @BeforeClass
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterClass
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    /**
     * Test the requirement of responding with an http 400 when using a Sec-WebSocket-Version that is unsupported.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testRequestVersion29() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        // intentionally bad version
        request.header(HttpHeader.SEC_WEBSOCKET_VERSION, "29");

        Future<BlockheadConnection> connFut = request.sendAsync();

        expectedException.expect(ExecutionException.class);
        expectedException.expectCause(instanceOf(UpgradeException.class));
        expectedException.expectMessage(containsString("400 Unsupported websocket version specification"));

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
        }
    }
}
