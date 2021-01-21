//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class FirefoxTest
{
    private static BlockheadClient client;
    private static SimpleServletServer server;

    @BeforeAll
    public static void startContainers() throws Exception
    {
        server = new SimpleServletServer(new MyEchoServlet());
        server.start();

        client = new BlockheadClient();
        client.start();
    }

    @AfterAll
    public static void stopContainers() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testConnectionKeepAlive() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());

        // Odd Connection Header value seen in older Firefox versions
        request.header(HttpHeader.CONNECTION, "keep-alive, Upgrade");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection conn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            conn.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<WebSocketFrame> frames = conn.getFrameQueue();
            WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Text Frame.status code", tf.getPayloadAsUTF8(), is(msg));
        }
    }
}
