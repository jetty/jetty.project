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

package org.eclipse.jetty.websocket.core.extensions;

import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.jupiter.TestTrackerExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.BatchMode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.RawFrameBuilder;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.TestWebSocketNegotiator;
import org.eclipse.jetty.websocket.core.TestWebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketServerTest;
import org.eclipse.jetty.websocket.core.WebSocketTester;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestTrackerExtension.class)
public class ValidationExtensionTest extends WebSocketTester
{
    private static Logger LOG = Log.getLogger(WebSocketServerTest.class);

    private WebSocketServer server;

    @Test
    public void testNonUtf8BinaryPayload() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        byte[] nonUtf8Payload = {0x7F,(byte)0xFF,(byte)0xFF};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, nonUtf8Payload, true));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.BINARY));
            assertThat(frame.getPayload().array(), is(nonUtf8Payload));


            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testValidContinuationOnNonUtf8Boundary() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0,(byte)0xA0};
        byte[] continuationPayload = new byte[]{(byte)0xAE,(byte)0x9F};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(frame.getPayload().array(), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, continuationPayload, true));
            frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
            assertThat(frame.getPayload().array(), is(continuationPayload));

            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testInvalidContinuationOnNonUtf8Boundary() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0,(byte)0xA0};
        byte[] incompleteContinuationPayload = new byte[]{(byte)0xAE};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(frame.getPayload().array(), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, incompleteContinuationPayload, true));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.BAD_PAYLOAD));
        }
    }




    static class WebSocketServer extends AbstractLifeCycle
    {
        private static Logger LOG = Log.getLogger(WebSocketServer.class);
        private final Server server;
        private final TestFrameHandler handler;

        public void doStart() throws Exception
        {
            server.start();
        }

        public void doStop() throws Exception
        {
            server.stop();
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
            WebSocketNegotiator negotiator = new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(),
                    connector.getByteBufferPool(), frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new TestWebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
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
            return handler.getCoreSession().isOpen();
        }
    }
}
