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
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.IO;
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

/**
 * Test various {@link javax.websocket.Decoder.TextStream Decoder.TextStream} and {@link javax.websocket.Encoder.TextStream Encoder.TextStream} echo behavior of Java Readers
 */
public class ReaderEchoTest
{
    private static final Logger LOG = Log.getLogger(ReaderEchoTest.class);
    
    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/reader")
    public static class ReaderSocket extends BaseSocket
    {
        @OnMessage
        public String onReader(Reader reader) throws IOException
        {
            return IO.toString(reader);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/reader-param/{param}")
    public static class ReaderParamSocket extends BaseSocket
    {
        @OnMessage
        public String onReader(Reader reader, @PathParam("param") String param) throws IOException
        {
            StringBuilder msg = new StringBuilder();
            msg.append(IO.toString(reader));
            msg.append('|');
            msg.append(param);
            return msg.toString();
        }
    }
    
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.getServerContainer().addEndpoint(ReaderSocket.class);
        server.getServerContainer().addEndpoint(ReaderParamSocket.class);
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testReaderSocket() throws Exception
    {
        String requestPath = "/echo/reader";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello World"));
        send.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("Hello World"));
        expect.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
        
        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testReaderParamSocket() throws Exception
    {
        String requestPath = "/echo/reader-param/Every%20Person";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello World"));
        send.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("Hello World|Every Person"));
        expect.add(new CloseFrame().setPayload(CloseStatus.NORMAL));
        
        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
