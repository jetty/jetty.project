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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Fuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class IdleTimeoutTest
{
    @WebSocket(maxIdleTime = 500)
    public static class FastTimeoutSocket
    {
    }
    
    @SuppressWarnings("serial")
    public static class TimeoutServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(FastTimeoutSocket.class);
        }
    }
    
    protected static SimpleServletServer server;
    
    @Rule
    public TestName testname = new TestName();
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new TimeoutServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    /**
     * Test IdleTimeout on server.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testIdleTimeout() throws Exception
    {
        try (Fuzzer session = server.newNetworkFuzzer())
        {
            // wait 1 second to allow timeout to fire off
            TimeUnit.SECONDS.sleep(1);
            
            session.sendFrames(new TextFrame().setPayload("You shouldn't be there"));
            
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CLOSE));

            CloseStatus closeStatus = CloseFrame.toCloseStatus(frame.getPayload());
            assertThat("Close.statusCode", closeStatus.getCode(), is(StatusCode.SHUTDOWN));
            assertThat("Close.reason", closeStatus.getReason(), containsString("Timeout"));
        }
    }
}
