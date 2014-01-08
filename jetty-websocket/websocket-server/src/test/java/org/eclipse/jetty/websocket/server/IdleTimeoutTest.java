//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class IdleTimeoutTest
{
    @SuppressWarnings("serial")
    public static class TimeoutServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.getPolicy().setIdleTimeout(500);
            factory.register(RFCSocket.class);
        }
    }

    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new TimeoutServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test IdleTimeout on server.
     */
    @Test
    public void testIdleTimeout() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("onConnect");
        client.setTimeout(TimeUnit.MILLISECONDS,2500);
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // This wait should be shorter than client timeout above, but
            // longer than server timeout configured in TimeoutServlet
            client.sleep(TimeUnit.MILLISECONDS,1000);

            // Write to server
            // This action is possible, but does nothing.
            // Server could be in a half-closed state at this point.
            // Where the server read is closed (due to timeout), but the server write is still open.
            // The server could not read this frame, if it is in this half closed state
            client.write(new TextFrame().setPayload("Hello"));

            // Expect server to be disconnected at this point
            client.expectServerDisconnect();
        }
        finally
        {
            client.close();
        }
    }
}
