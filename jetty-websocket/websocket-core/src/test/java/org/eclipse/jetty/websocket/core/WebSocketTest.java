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

package org.eclipse.jetty.websocket.core;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class WebSocketTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    
    private static Logger LOG = Log.getLogger(WebSocketTest.class);
    
    private WebSocketServer server;
    private WebSocketClient client;

    @Before
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
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                super.receivedFrames.offer(Frame.copy(frame));
                if(frame.getOpCode() == OpCode.CLOSE)
                {
                    LOG.info("channel aborted");
                    getChannel().abort();
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

        ((WebSocketChannel)client.handler.channel).getConnection().getEndPoint().close();
        
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

            this.client.getPolicy().setMaxBinaryMessageSize(20 * 1024 * 1024);
            this.client.getPolicy().setMaxTextMessageSize(20 * 1024 * 1024);
            this.handler = frameHandler;
        }

        public void start() throws Exception
        {
            WebSocketCoreClientUpgradeRequest request = new WebSocketCoreClientUpgradeRequest(client, baseWebSocketUri.resolve("/test"))
            {
                @Override
                public FrameHandler getFrameHandler(WebSocketCoreClient coreClient, WebSocketPolicy upgradePolicy, HttpResponse response)
                {
                    return handler;
                }
            };
            request.setSubProtocols("test");
            this.client.start();
            Future<FrameHandler.CoreSession> response = client.connect(request);
            response.get(5, TimeUnit.SECONDS);
        }


        public void sendFrame(Frame frame)
        {
            handler.getChannel().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }


        public void sendText(String line)
        {
            LOG.info("sending {}...", line);
            Frame frame = new Frame(OpCode.TEXT);
            frame.setFin(true);
            frame.setPayload(line);

            handler.getChannel().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getChannel().close(CloseStatus.NORMAL, "WebSocketClient Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getChannel().isOpen();
        }
    }


    static class TestFrameHandler implements FrameHandler
    {
        private CoreSession channel;

        private BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        private CountDownLatch closed = new CountDownLatch(1);

        public CoreSession getChannel()
        {
            return channel;
        }

        public BlockingQueue<Frame> getFrames()
        {
            return receivedFrames;
        }

        @Override
        public void onOpen(CoreSession coreSession) throws Exception
        {
            LOG.info("onOpen {}", coreSession);
            this.channel = coreSession;
        }

        @Override
        public void onReceiveFrame(Frame frame, Callback callback)
        {
            LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
            receivedFrames.offer(Frame.copy(frame)); //needs to copy because frame is no longer valid after callback.succeeded();
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus) throws Exception
        {
            LOG.info("onClosed {}",closeStatus);
            closed.countDown();
        }

        @Override
        public void onError(Throwable cause) throws Exception
        {
            LOG.info("onError {} ", cause.toString());
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

            connector.addBean(new WebSocketPolicy(WebSocketBehavior.SERVER));
            connector.addBean(new RFC6455Handshaker());
            connector.setPort(port);
            connector.setIdleTimeout(1000000);
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            server.setHandler(context);
            WebSocketNegotiator negotiator =  new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(), connector.getByteBufferPool(), port, frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
            upgradeHandler.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getOutputStream().println("Hello World!");
                    baseRequest.setHandled(true);
                }
            });
        }

        public void sendFrame(Frame frame)
        {
            handler.getChannel().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }

        public void sendText(String line)
        {
            LOG.info("sending {}...", line);
            Frame frame = new Frame(OpCode.TEXT);
            frame.setFin(true);
            frame.setPayload(line);

            handler.getChannel().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getChannel().close(CloseStatus.NORMAL, "WebSocketServer Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getChannel().isOpen();
        }

    }


    static class TestWebSocketNegotiator implements WebSocketNegotiator
    {
        final DecoratedObjectFactory objectFactory;
        final WebSocketExtensionRegistry extensionRegistry;
        final ByteBufferPool bufferPool;
        private final int port;
        private final FrameHandler frameHandler;

        public TestWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool, int port, FrameHandler frameHandler)
        {
            this.objectFactory = objectFactory;
            this.extensionRegistry = extensionRegistry;
            this.bufferPool = bufferPool;
            this.port = port;
            this.frameHandler = frameHandler;
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
        {
            List<String> offeredSubprotocols = negotiation.getOfferedSubprotocols();
            if (!offeredSubprotocols.contains("test"))
                return null;
            negotiation.setSubprotocol("test");
            return frameHandler;
        }

        @Override
        public WebSocketPolicy getCandidatePolicy()
        {
            return null;
        }

        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return extensionRegistry;
        }

        @Override
        public DecoratedObjectFactory getObjectFactory()
        {
            return objectFactory;
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }
    }
}
