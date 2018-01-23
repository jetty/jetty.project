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

package org.eclipse.jetty.websocket.jsr356.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.jsr356.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.jsr356.tests.WSServer;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.IdleTimeoutOnOpenEndpoint;
import org.eclipse.jetty.websocket.jsr356.tests.server.sockets.IdleTimeoutOnOpenSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class IdleTimeoutTest
{
    private static WSServer server;
    
    @BeforeClass
    public static void setupServer() throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingPath(IdleTimeoutTest.class.getName()), "app");
        server.copyWebInf("idle-timeout-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        server.copyClass(IdleTimeoutOnOpenEndpoint.class);
        // the configuration that adds the endpoint
        server.copyClass(IdleTimeoutContextListener.class);
        // the annotated socket
        server.copyClass(IdleTimeoutOnOpenSocket.class);
        
        server.start();
        
        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
        // wsb.dump();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    private void assertConnectionTimeout(String requestPath) throws Exception
    {
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            // wait 1 second to allow timeout to fire off
            TimeUnit.SECONDS.sleep(1);
            
            session.sendFrames(new TextFrame().setPayload("You shouldn't be there"));
            
            BlockingQueue<WebSocketFrame> framesQueue = session.getOutputFrames();
            WebSocketFrame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.CLOSE));
            CloseStatus closeStatus = CloseFrame.toCloseStatus(frame.getPayload());
            assertThat("Close.statusCode", closeStatus.getCode(), is(CloseStatus.SHUTDOWN));
            assertThat("Close.reason", closeStatus.getReason(), containsString("Timeout"));
        }
    }
    
    @Test
    public void testAnnotated() throws Exception
    {
        assertConnectionTimeout("/app/idle-onopen-socket");
    }
    
    @Test
    public void testEndpoint() throws Exception
    {
        assertConnectionTimeout("/app/idle-onopen-endpoint");
    }
}
