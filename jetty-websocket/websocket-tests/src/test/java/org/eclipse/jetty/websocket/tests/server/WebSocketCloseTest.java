//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConstants;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests various close scenarios
 */
public class WebSocketCloseTest
{
    /**
     * On Message, return container information
     */
    public static class ContainerSocket extends WebSocketAdapter
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
            LOG.debug("onWebSocketText({})", message);
            if (message.equalsIgnoreCase("openSessions"))
            {
                try
                {
                    Collection<WebSocketSession> sessions = container.getOpenSessions();
                    
                    StringBuilder ret = new StringBuilder();
                    ret.append("openSessions.size=").append(sessions.size()).append('\n');
                    int idx = 0;
                    for (WebSocketSession sess : sessions)
                    {
                        ret.append('[').append(idx++).append("] ").append(sess.toString()).append('\n');
                    }
                    session.getRemote().sendString(ret.toString());
                }
                catch (IOException e)
                {
                    LOG.warn(e);
                }
            }
            session.close(StatusCode.NORMAL, "ContainerSocket");
        }
        
        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})", sess);
            this.session = sess;
        }
    }
    
    /**
     * On Connect, close socket
     */
    public static class FastCloseSocket extends WebSocketAdapter
    {
        private static final Logger LOG = Log.getLogger(WebSocketCloseTest.FastCloseSocket.class);
        
        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})", sess);
            sess.close(StatusCode.NORMAL, "FastCloseServer");
        }
    }
    
    /**
     * On Connect, throw unhandled exception
     */
    public static class FastFailSocket extends WebSocketAdapter
    {
        private static final Logger LOG = Log.getLogger(WebSocketCloseTest.FastFailSocket.class);
        
        @Override
        public void onWebSocketConnect(Session sess)
        {
            LOG.debug("onWebSocketConnect({})", sess);
            // Test failure due to unhandled exception
            // this should trigger a fast-fail closure during open/connect
            throw new RuntimeException("Intentional FastFail");
        }
    }
    
    /**
     * On Message, drop connection
     */
    public static class DropServerConnectionSocket extends WebSocketAdapter
    {
        @Override
        public void onWebSocketText(String message)
        {
            try
            {
                getSession().disconnect();
            }
            catch (IOException ignore)
            {
            }
        }
    }
    
    public static class CloseServlet extends WebSocketServlet implements WebSocketCreator
    {
        private WebSocketServerFactory serverFactory;
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
            if (factory instanceof WebSocketServerFactory)
            {
                this.serverFactory = (WebSocketServerFactory) factory;
            }
        }
        
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("fastclose"))
            {
                return new FastCloseSocket();
            }
            
            if (req.hasSubProtocol("fastfail"))
            {
                return new FastFailSocket();
            }
            
            if (req.hasSubProtocol("drop"))
            {
                return new DropServerConnectionSocket();
            }
            
            if (req.hasSubProtocol("container"))
            {
                return new ContainerSocket(serverFactory);
            }
            
            return new RFC6455Socket();
        }
    }
    
    private SimpleServletServer server;
    
    @Before
    public void startServer() throws Exception
    {
        server = new SimpleServletServer(new CloseServlet());
        server.start();
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    /**
     * Test fast close (bug #403817)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastClose() throws Exception
    {
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, "fastclose");
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.NORMAL, "FastCloseServer").asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            session.sendFrames(new CloseInfo(StatusCode.NORMAL).asFrame());
            session.expect(expect);
        }
    }
    
    /**
     * Test fast fail (bug #410537)
     *
     * @throws Exception on test failure
     */
    @Test
    public void fastFail() throws Exception
    {
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, "fastfail");
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.SERVER_ERROR).asFrame());
        
        try (StacklessLogging ignore = new StacklessLogging(FastFailSocket.class);
             LocalFuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            session.expect(expect);
        }
    }
    
    @Test
    public void dropServerConnection() throws Exception
    {
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, "drop");
        
        try (LocalFuzzer session = server.newLocalFuzzer("/", upgradeHeaders))
        {
            session.sendFrames(new TextFrame().setPayload("drop"));
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            assertThat("No frames as output", framesQueue.size(), Matchers.is(0));
        }
    }

    /**
     *
     * @throws Exception on test failure
     */
    @Test
    public void testFastFailFastClose() throws Exception
    {
        fastFail();
        fastClose();
    }


    /**
     * Test session open session cleanup (bug #474936)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testOpenSessionCleanup() throws Exception
    {
        fastFail();
        fastClose();
        dropClientConnection();
        
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, "container");
        
        try (LocalFuzzer session = server.newLocalFuzzer("/?openSessions", upgradeHeaders))
        {
            session.sendFrames(
                    new TextFrame().setPayload("openSessions"),
                    new CloseInfo(StatusCode.NORMAL).asFrame()
            );
            
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(3, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("openSessions.size=1\n"));
        }
    }
    
    private void dropClientConnection() throws Exception
    {
        Map<String, String> upgradeHeaders = UpgradeUtils.newDefaultUpgradeRequestHeaders();
        upgradeHeaders.put(WebSocketConstants.SEC_WEBSOCKET_PROTOCOL, "container");
        
        try (LocalFuzzer ignored = server.newLocalFuzzer("/", upgradeHeaders))
        {
            // do nothing, just let endpoint close
        }
    }
}
