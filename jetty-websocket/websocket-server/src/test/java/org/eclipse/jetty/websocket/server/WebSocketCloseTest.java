//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.eclipse.jetty.websocket.server.helper.RFCSocket;
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
    @SuppressWarnings("serial")
    public static class CloseServlet extends WebSocketServlet implements WebSocketCreator
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
        }

        @Override
        public Object createWebSocket(UpgradeRequest req, UpgradeResponse resp)
        {
            if (req.hasSubProtocol("fastclose"))
            {
                fastcloseSocket = new FastCloseSocket();
                return fastcloseSocket;
            }
            return new RFCSocket();
        }
    }

    public static class FastCloseSocket extends WebSocketAdapter
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
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})",sess);
            try
            {
                sess.close();
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            errors.add(cause);
        }
    }

    private static final Logger LOG = Log.getLogger(WebSocketCloseTest.class);
    private static SimpleServletServer server;
    private static FastCloseSocket fastcloseSocket;

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
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("fastclose");
        client.setTimeout(TimeUnit.SECONDS,1);
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = capture.getFrames().get(0);
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.NORMAL));

            client.write(close.asFrame()); // respond with close

            Assert.assertThat("Fast Close Latch",fastcloseSocket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
            Assert.assertThat("Fast Close.statusCode",fastcloseSocket.closeStatusCode,is(StatusCode.NORMAL));
        }
        finally
        {
            client.close();
        }
    }
}
