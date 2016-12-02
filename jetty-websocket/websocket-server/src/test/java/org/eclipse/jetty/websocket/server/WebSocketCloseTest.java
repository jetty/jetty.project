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
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.IBlockheadClient;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests various close scenarios
 */
public class WebSocketCloseTest
{
    static class AbstractCloseSocket extends WebSocketAdapter
    {
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public String closeReason = null;
        public int closeStatusCode = -1;
        public List<Throwable> errors = new ArrayList<>();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            LOG.debug("onWebSocketClose({}, {})",statusCode,reason);
            this.closeStatusCode = statusCode;
            this.closeReason = reason;
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            errors.add(cause);
        }
    }

    @SuppressWarnings("serial")
    public static class CloseServlet extends WebSocketServlet implements WebSocketCreator
    {
        private WebSocketServerFactory serverFactory;

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
            if (factory instanceof WebSocketServerFactory)
            {
                this.serverFactory = (WebSocketServerFactory)factory;
            }
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("fastclose"))
            {
                closeSocket = new FastCloseSocket();
                return closeSocket;
            }

            if (req.hasSubProtocol("fastfail"))
            {
                closeSocket = new FastFailSocket();
                return closeSocket;
            }

            if (req.hasSubProtocol("container"))
            {
                closeSocket = new ContainerSocket(serverFactory);
                return closeSocket;
            }
            return new RFCSocket();
        }
    }

    /**
     * On Message, return container information
     */
    public static class ContainerSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(WebSocketCloseTest.ContainerSocket.class);
        private final WebSocketServerFactory container;
        private Session session;

        public ContainerSocket(WebSocketServerFactory container)
        {
            this.container = container;
        }

        @Override
        public void onWebSocketText(String message)
        {
            LOG.debug("onWebSocketText({})",message);
            if (message.equalsIgnoreCase("openSessions"))
            {
                Collection<WebSocketSession> sessions = container.getOpenSessions();

                StringBuilder ret = new StringBuilder();
                ret.append("openSessions.size=").append(sessions.size()).append('\n');
                int idx = 0;
                for (WebSocketSession sess : sessions)
                {
                    ret.append('[').append(idx++).append("] ").append(sess.toString()).append('\n');
                }
                session.getRemote().sendStringByFuture(ret.toString());
            }
            session.close(StatusCode.NORMAL,"ContainerSocket");
        }

        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            this.session = sess;
        }
    }

    /**
     * On Connect, close socket
     */
    public static class FastCloseSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(WebSocketCloseTest.FastCloseSocket.class);

        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            sess.close(StatusCode.NORMAL,"FastCloseServer");
        }
    }

    /**
     * On Connect, throw unhandled exception
     */
    public static class FastFailSocket extends AbstractCloseSocket
    {
        private static final Logger LOG = Log.getLogger(WebSocketCloseTest.FastFailSocket.class);

        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            // Test failure due to unhandled exception
            // this should trigger a fast-fail closure during open/connect
            throw new RuntimeException("Intentional FastFail");
        }
    }

    private static final Logger LOG = Log.getLogger(WebSocketCloseTest.class);

    private static SimpleServletServer server;
    private static AbstractCloseSocket closeSocket;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new CloseServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test fast close (bug #403817)
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFastClose() throws Exception
    {
        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastclose");
            client.setTimeout(1,TimeUnit.SECONDS);
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Verify that client got close frame
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));

            // Notify server of close handshake
            client.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Fast Close Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("Fast Close.statusCode",closeSocket.closeStatusCode,is(StatusCode.NORMAL));
        }
    }

    /**
     * Test fast fail (bug #410537)
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFastFail() throws Exception
    {
        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastfail");
            client.setTimeout(1,TimeUnit.SECONDS);
            try (StacklessLogging scope = new StacklessLogging(FastFailSocket.class, WebSocketSession.class))
            {
                client.connect();
                client.sendStandardRequest();
                client.expectUpgradeResponse();

                EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
                WebSocketFrame frame = frames.poll();
                assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
                CloseInfo close = new CloseInfo(frame);
                assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));

                client.write(close.asFrame()); // respond with close

                // ensure server socket got close event
                assertThat("Fast Fail Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
                assertThat("Fast Fail.statusCode",closeSocket.closeStatusCode,is(StatusCode.SERVER_ERROR));
                assertThat("Fast Fail.errors",closeSocket.errors.size(),is(1));
            }
        }
    }

    /**
     * Test session open session cleanup (bug #474936)
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testOpenSessionCleanup() throws Exception
    {
        fastFail();
        fastClose();
        dropConnection();

        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("container");
            client.setTimeout(1,TimeUnit.SECONDS);
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            TextFrame text = new TextFrame();
            text.setPayload("openSessions");
            client.write(text);

            EventQueue<WebSocketFrame> frames = client.readFrames(2,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.TEXT));

            String resp = frame.getPayloadAsUTF8();
            assertThat("Should only have 1 open session",resp,containsString("openSessions.size=1\n"));

            frame = frames.poll();
            assertThat("frames[1].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));
            client.write(close.asFrame()); // respond with close

            // ensure server socket got close event
            assertThat("Open Sessions Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            assertThat("Open Sessions.statusCode",closeSocket.closeStatusCode,is(StatusCode.NORMAL));
            assertThat("Open Sessions.errors",closeSocket.errors.size(),is(0));
        }
    }

    @SuppressWarnings("Duplicates")
    private void fastClose() throws Exception
    {
        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastclose");
            client.setTimeout(1,TimeUnit.SECONDS);
            try (StacklessLogging scope = new StacklessLogging(WebSocketSession.class))
            {
                client.connect();
                client.sendStandardRequest();
                client.expectUpgradeResponse();
                
                client.readFrames(1,1,TimeUnit.SECONDS);

                CloseInfo close = new CloseInfo(StatusCode.NORMAL,"Normal");
                assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));

                // Notify server of close handshake
                client.write(close.asFrame()); // respond with close

                // ensure server socket got close event
                assertThat("Fast Close Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
                assertThat("Fast Close.statusCode",closeSocket.closeStatusCode,is(StatusCode.NORMAL));
            }
        }
    }

    private void fastFail() throws Exception
    {
        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastfail");
            client.setTimeout(1,TimeUnit.SECONDS);
            try (StacklessLogging scope = new StacklessLogging(WebSocketSession.class))
            {
                client.connect();
                client.sendStandardRequest();
                client.expectUpgradeResponse();
                
                client.readFrames(1,1,TimeUnit.SECONDS);

                CloseInfo close = new CloseInfo(StatusCode.NORMAL,"Normal");
                client.write(close.asFrame()); // respond with close

                // ensure server socket got close event
                assertThat("Fast Fail Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
                assertThat("Fast Fail.statusCode",closeSocket.closeStatusCode,is(StatusCode.SERVER_ERROR));
                assertThat("Fast Fail.errors",closeSocket.errors.size(),is(1));
            }
        }
    }
    
    @SuppressWarnings("Duplicates")
    private void dropConnection() throws Exception
    {
        try (IBlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("container");
            client.setTimeout(1,TimeUnit.SECONDS);
            try (StacklessLogging scope = new StacklessLogging(WebSocketSession.class))
            {
                client.connect();
                client.sendStandardRequest();
                client.expectUpgradeResponse();
                client.disconnect();
            }
        }
    }
}
