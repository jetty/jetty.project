//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketServer;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of a core client and core server
 */
public class WebSocketClientServerTest
{
    private static Logger LOG = Log.getLogger(WebSocketClientServerTest.class);

    private WebSocketServer server;
    private TestFrameHandler serverHandler;
    private URI serverUri;

    private WebSocketCoreClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        serverHandler = new TestFrameHandler();
        server = new WebSocketServer(serverHandler);
        server.start();
        serverUri = new URI("ws://localhost:" + server.getLocalPort());

        client = new WebSocketCoreClient();
        client.start();
    }

    @Test
    public void testHello() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, serverUri);
        connect.get(5, TimeUnit.SECONDS);

        String message = "hello world";
        clientHandler.sendText(message);
        Frame recv = serverHandler.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        message = "back at ya!";
        serverHandler.sendText(message);
        recv = clientHandler.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        clientHandler.sendClose();

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSocketClosedInCloseHandshake() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler()
        {
            @Override
            public void onFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                super.receivedFrames.offer(Frame.copy(frame));
                if (frame.getOpCode() == OpCode.CLOSE)
                {
                    LOG.info("channel aborted");
                    getCoreSession().abort();
                    callback.failed(new Exception());
                }
                else
                {
                    callback.succeeded();
                }
            }
        };
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, serverUri);
        connect.get(5, TimeUnit.SECONDS);

        String message = "hello world";
        serverHandler.sendText(message);
        Frame recv = clientHandler.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        serverHandler.sendClose();

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSocketClosed() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, serverUri);
        connect.get(5, TimeUnit.SECONDS);

        String message = "hello world";
        clientHandler.sendText(message);
        Frame recv = serverHandler.getFrames().poll(2, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        ((WebSocketChannel)clientHandler.getCoreSession()).getConnection().getEndPoint().close();

        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
    }
}
