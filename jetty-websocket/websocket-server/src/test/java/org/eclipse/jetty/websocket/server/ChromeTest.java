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
import static org.hamcrest.Matchers.is;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChromeTest
{
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

    @Test
    public void testUpgradeWithWebkitDeflateExtension() throws Exception
    {
        Assume.assumeTrue("Server has x-webkit-deflate-frame registered",
                server.getWebSocketServletFactory().getExtensionFactory().isAvailable("x-webkit-deflate-frame"));

        Assume.assumeTrue("Client has x-webkit-deflate-frame registered",
                client.getExtensionFactory().isAvailable("x-webkit-deflate-frame"));

        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "x-webkit-deflate-frame");
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "chat");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            HttpFields responseFields = clientConn.getUpgradeResponseHeaders();
            HttpField extensionField = responseFields.getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
            Assert.assertThat("Response", extensionField.getValue(),containsString("x-webkit-deflate-frame"));

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            clientConn.write(new TextFrame().setPayload(msg));

            // Read frame (hopefully text frame)
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
    }
}
