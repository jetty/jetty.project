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

import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.IBlockheadClient;
import org.eclipse.jetty.websocket.server.helper.SessionServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Testing various aspects of the server side support for WebSocket {@link org.eclipse.jetty.websocket.api.Session}
 */
@RunWith(AdvancedRunner.class)
public class WebSocketServerSessionTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testDisconnect() throws Exception
    {
        URI uri = server.getServerUri().resolve("/test/disconnect");
        try (IBlockheadClient client = new BlockheadClient(uri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("harsh-disconnect"));

            client.awaitDisconnect(1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testUpgradeRequestResponse() throws Exception
    {
        URI uri = server.getServerUri().resolve("/test?snack=cashews&amount=handful&brand=off");
        try (IBlockheadClient client = new BlockheadClient(uri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Ask the server socket for specific parameter map info
            client.write(new TextFrame().setPayload("getParameterMap|snack"));
            client.write(new TextFrame().setPayload("getParameterMap|amount"));
            client.write(new TextFrame().setPayload("getParameterMap|brand"));
            client.write(new TextFrame().setPayload("getParameterMap|cost")); // intentionally invalid

            // Read frame (hopefully text frame)
            EventQueue<WebSocketFrame> frames = client.readFrames(4,5,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Parameter Map[snack]", tf.getPayloadAsUTF8(), is("[cashews]"));
            tf = frames.poll();
            Assert.assertThat("Parameter Map[amount]", tf.getPayloadAsUTF8(), is("[handful]"));
            tf = frames.poll();
            Assert.assertThat("Parameter Map[brand]", tf.getPayloadAsUTF8(), is("[off]"));
            tf = frames.poll();
            Assert.assertThat("Parameter Map[cost]", tf.getPayloadAsUTF8(), is("<null>"));
        }
    }
}
