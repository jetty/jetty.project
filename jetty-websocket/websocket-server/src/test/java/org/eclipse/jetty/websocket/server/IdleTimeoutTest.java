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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.Assert;
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
     * @throws Exception on test failure
     */
    @Test
    public void testIdleTimeout() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("onConnect");
        client.setTimeout(2500,TimeUnit.MILLISECONDS);
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

            // Expect server to have closed due to its own timeout
            EventQueue<WebSocketFrame> frames = client.readFrames(1,30,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("frame opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("close code",close.getStatusCode(),is(StatusCode.SHUTDOWN));
            Assert.assertThat("close reason",close.getReason(),containsString("Timeout"));
        }
        finally
        {
            client.close();
        }
    }
}
