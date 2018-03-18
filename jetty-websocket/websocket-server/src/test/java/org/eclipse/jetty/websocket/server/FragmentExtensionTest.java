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
import org.eclipse.jetty.websocket.server.helper.EchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class FragmentExtensionTest
{
    private static SimpleServletServer server;
    private static BlockheadClient client;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
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

    private String[] split(String str, int partSize)
    {
        int strLength = str.length();
        int count = (int)Math.ceil((double)str.length() / partSize);
        String ret[] = new String[count];
        int idx;
        for (int i = 0; i < count; i++)
        {
            idx = (i * partSize);
            ret[i] = str.substring(idx,Math.min(idx + partSize,strLength));
        }
        return ret;
    }

    @Test
    public void testFragmentExtension() throws Exception
    {
        Assume.assumeTrue("Server has fragment registered",
                server.getWebSocketServletFactory().getExtensionFactory().isAvailable("fragment"));

        Assume.assumeTrue("Client has fragment registered",
                client.getExtensionFactory().isAvailable("fragment"));

        int fragSize = 4;

        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());
        request.header(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "fragment;maxLength=" + fragSize);
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "onConnect");
        request.idleTimeout(1, TimeUnit.SECONDS);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Make sure the read times out if there are problems with the implementation
            HttpFields responseHeaders = clientConn.getUpgradeResponseHeaders();
            HttpField extensionHeader = responseHeaders.getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);

            Assert.assertThat("Response",extensionHeader.getValue(),containsString("fragment"));

            String msg = "Sent as a long message that should be split";
            clientConn.write(new TextFrame().setPayload(msg));

            String parts[] = split(msg,fragSize);
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            for (int i = 0; i < parts.length; i++)
            {
                WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                Assert.assertThat("text[" + i + "].payload",frame.getPayloadAsUTF8(),is(parts[i]));
            }
        }
    }
}
