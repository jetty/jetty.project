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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TextStreamTest
{
    private static final String PATH = "/echo";
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static LocalServer server;
    private static ServerContainer container;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer()
        {
            @Override
            protected void configureServletContextHandler(ServletContextHandler context) throws Exception
            {
                container = WebSocketServerContainerInitializer.configureContext(context);
                ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerTextStreamer.class, PATH).build();
                container.addEndpoint(config);
            }
        };
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEchoWithMediumMessage() throws Exception
    {
        testEcho(1024);
    }

    @Test
    public void testLargestMessage() throws Exception
    {
        testEcho(container.getDefaultMaxTextMessageBufferSize());
    }
    
    private byte[] newData(int size)
    {
        byte[] pattern = "01234567890abcdefghijlklmopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
        {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }
    
    private void testEcho(int size) throws Exception
    {
        byte[] data = newData(size);
    
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(data)));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        ByteBuffer expectedMessage = DataUtils.copyOf(data);
    
        try (LocalFuzzer session = server.newLocalFuzzer("/echo"))
        {
            session.sendBulk(send);
            BlockingQueue<WebSocketFrame> receivedFrames = session.getOutputFrames();
            session.expectMessage(receivedFrames, OpCode.TEXT, expectedMessage);
        }
    }
    
    @Test
    public void testMoreThanLargestMessageOneByteAtATime() throws Exception
    {
        int size = container.getDefaultMaxTextMessageBufferSize() + 16;
        byte[] data = newData(size);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(data)));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        ByteBuffer expectedMessage = DataUtils.copyOf(data);
    
        try (LocalFuzzer session = server.newLocalFuzzer("/echo"))
        {
            session.sendBulk(send);
            BlockingQueue<WebSocketFrame> receivedFrames = session.getOutputFrames();
            session.expectMessage(receivedFrames, OpCode.TEXT, expectedMessage);
        }
    }

    @ServerEndpoint(PATH)
    public static class ServerTextStreamer
    {
        @OnMessage
        public void echo(Session session, Reader input) throws IOException
        {
            char[] buffer = new char[128];
            try (Writer output = session.getBasicRemote().getSendWriter())
            {
                int read;
                while ((read = input.read(buffer)) >= 0)
                    output.write(buffer, 0, read);
            }
        }
    }
}
