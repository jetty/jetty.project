//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
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
import org.eclipse.jetty.websocket.common.events.AbstractEventDriver;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.Assert;
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
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
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
            return new RFCSocket();
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
     */
    @Test
    public void testFastClose() throws Exception
    {
        try (BlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastclose");
            client.setTimeout(1,TimeUnit.SECONDS);
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Verify that client got close frame
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));
            
            // Notify server of close handshake
            client.write(close.asFrame()); // respond with close
            
            // ensure server socket got close event
            Assert.assertThat("Fast Close Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            Assert.assertThat("Fast Close.statusCode",closeSocket.closeStatusCode,is(StatusCode.NORMAL));
        }
    }

    /**
     * Test fast fail (bug #410537)
     */
    @Test
    public void testFastFail() throws Exception
    {
        try (BlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setProtocols("fastfail");
            client.setTimeout(1,TimeUnit.SECONDS);
            try (StacklessLogging scope = new StacklessLogging(AbstractEventDriver.class))
            {
                client.connect();
                client.sendStandardRequest();
                client.expectUpgradeResponse();

                EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
                WebSocketFrame frame = frames.poll();
                Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
                CloseInfo close = new CloseInfo(frame);
                Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));

                client.write(close.asFrame()); // respond with close

                // ensure server socket got close event
                Assert.assertThat("Fast Fail Latch",closeSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
                Assert.assertThat("Fast Fail.statusCode",closeSocket.closeStatusCode,is(StatusCode.SERVER_ERROR));
                Assert.assertThat("Fast Fail.errors",closeSocket.errors.size(),is(1));
            }
        }
    }
}
