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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.tests.Fuzzer;
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
        
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload("getParameterMap|snack"));
        send.add(new Frame(OpCode.TEXT).setPayload("getParameterMap|amount"));
        send.add(new Frame(OpCode.TEXT).setPayload("getParameterMap|brand"));
        send.add(new Frame(OpCode.TEXT).setPayload("getParameterMap|cost"));
        send.add(new Frame(OpCode.CLOSE));
        
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload("[cashews]"));
        expect.add(new Frame(OpCode.TEXT).setPayload("[handful]"));
        expect.add(new Frame(OpCode.TEXT).setPayload("[off]"));
        expect.add(new Frame(OpCode.TEXT).setPayload("<null>"));
        send.add(new Frame(OpCode.CLOSE));
        
        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
}
