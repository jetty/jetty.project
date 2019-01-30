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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.TestWebSocketNegotiator;
import org.eclipse.jetty.websocket.core.TestWebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.server.internal.RFC6455Handshaker;
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
    private WebSocketClient client;

    @BeforeEach
    public void setup() throws Exception
    {
    }

    @Test
    public void testHello() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();
        TestFrameHandler clientHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();
        client = new WebSocketClient("localhost", server.getLocalPort(), clientHandler);
        client.start();

        String message = "hello world";
        client.sendText(message);
        Frame recv = server.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        message = "back at ya!";
        server.sendText(message);
        recv = client.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        client.close();

        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(client.handler.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSocketClosedInCloseHandshake() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();
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

        server = new WebSocketServer(0, serverHandler);
        server.start();
        client = new WebSocketClient("localhost", server.getLocalPort(), clientHandler);
        client.start();

        String message = "hello world";
        server.sendText(message);
        Frame recv = client.getFrames().poll(5, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        server.close();

        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(client.handler.closed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSocketClosed() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();
        TestFrameHandler clientHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();
        client = new WebSocketClient("localhost", server.getLocalPort(), clientHandler);
        client.start();

        String message = "hello world";
        client.sendText(message);
        Frame recv = server.getFrames().poll(2, TimeUnit.SECONDS);
        assertNotNull(recv);
        assertThat(recv.getPayloadAsUTF8(), Matchers.equalTo(message));

        ((WebSocketChannel)client.handler.getCoreSession()).getConnection().getEndPoint().close();

        assertTrue(client.handler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
    }

    static class WebSocketClient
    {
        private static Logger LOG = Log.getLogger(WebSocketClient.class);

        private URI baseWebSocketUri;
        private WebSocketCoreClient client;
        private TestFrameHandler handler;

        public WebSocketClient(String hostname, int port, TestFrameHandler frameHandler) throws Exception
        {
            this.baseWebSocketUri = new URI("ws://" + hostname + ":" + port);
            this.client = new WebSocketCoreClient();
            this.handler = frameHandler;
        }

        public void start() throws Exception
        {
            UpgradeRequest request = UpgradeRequest.from(client, baseWebSocketUri.resolve("/test"), handler);
            request.setSubProtocols("test");
            this.client.start();
            Future<FrameHandler.CoreSession> response = client.connect(request);
            response.get(5, TimeUnit.SECONDS);
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, Callback.NOOP, false);
        }

        public void sendText(String line)
        {
            LOG.info("sending {}...", line);
            handler.sendText(line);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getCoreSession().close(CloseStatus.NORMAL, "WebSocketClient Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getCoreSession().isOutputOpen();
        }
    }

    static class WebSocketServer
    {
        private static Logger LOG = Log.getLogger(WebSocketServer.class);
        private final Server server;
        private final TestFrameHandler handler;

        public void start() throws Exception
        {
            server.start();
        }

        public int getLocalPort()
        {
            return server.getBean(NetworkConnector.class).getLocalPort();
        }

        public WebSocketServer(int port, TestFrameHandler frameHandler)
        {
            this.handler = frameHandler;
            server = new Server();
            server.getBean(QueuedThreadPool.class).setName("WSCoreServer");
            ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

            connector.addBean(new RFC6455Handshaker());
            connector.setPort(port);
            connector.setIdleTimeout(1000000);
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            server.setHandler(context);
            WebSocketNegotiator negotiator = new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(),
                connector.getByteBufferPool(), frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new TestWebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, Callback.NOOP, false);
        }

        public void sendText(String line)
        {
            LOG.info("sending {}...", line);
            handler.sendText(line);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getCoreSession().close(CloseStatus.NORMAL, "WebSocketServer Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getCoreSession().isOutputOpen();
        }

    }
}
