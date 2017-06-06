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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Testing various aspects of the server side support for WebSocket {@link org.eclipse.jetty.websocket.api.Session}
 */
public class WebSocketServerSessionTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testUpgradeRequestResponse() throws Exception
    {
        String requestPath = "/test?snack=cashews&amount=handful&brand=off";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("getParameterMap|snack"));
        send.add(new TextFrame().setPayload("getParameterMap|amount"));
        send.add(new TextFrame().setPayload("getParameterMap|brand"));
        send.add(new TextFrame().setPayload("getParameterMap|cost"));
        send.add(new CloseFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("[cashews]"));
        expect.add(new TextFrame().setPayload("[handful]"));
        expect.add(new TextFrame().setPayload("[off]"));
        expect.add(new TextFrame().setPayload("<null>"));
        send.add(new CloseFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
}
