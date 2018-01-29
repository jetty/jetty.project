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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.jsr356.tests.Fuzzer;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UriTemplateParameterTest
{
    private static final Logger LOG = Log.getLogger(UriTemplateParameterTest.class);
    
    @ServerEndpoint("/echo/params/{a}/{b}")
    public static class IntParamTextSocket
    {
        @OnMessage
        public String onMessage(int i, @PathParam("a") int paramA, @PathParam("b") int paramB) throws IOException
        {
            return String.format("%,d|%,d|%,d", i, paramA, paramB);
        }
        
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }
    
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(IntParamTextSocket.class);
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testIntParams() throws Exception
    {
        String requestPath = "/echo/params/1234/5678";
    
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("9999"));
        send.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("9,999|1,234|5,678"));
        expect.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
    
        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
