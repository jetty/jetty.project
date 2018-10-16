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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.tests.jsr356.AbstractJsrTrackingSocket;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class DecoderReaderManySmallTest
{
    public static class EventId
    {
        public int eventId;
    }
    
    public static class EventIdDecoder implements Decoder.TextStream<EventId>
    {
        @Override
        public void init(EndpointConfig config)
        {
        }
        
        @Override
        public void destroy()
        {
        }
        
        @Override
        public EventId decode(Reader reader) throws DecodeException, IOException
        {
            EventId id = new EventId();
            try (BufferedReader buf = new BufferedReader(reader))
            {
                String line;
                while ((line = buf.readLine()) != null)
                {
                    id.eventId = Integer.parseInt(line);
                }
            }
            return id;
        }
    }
    
    @ClientEndpoint(decoders = EventIdDecoder.class, subprotocols = "eventids")
    public static class EventIdSocket extends AbstractJsrTrackingSocket
    {
        public BlockingQueue<EventId> messageQueue = new LinkedBlockingDeque<>();

        public EventIdSocket(TestInfo testInfo)
        {
            super(testInfo.getDisplayName());
        }
        
        @SuppressWarnings("unused")
        @OnMessage
        public void onMessage(EventId msg)
        {
            messageQueue.offer(msg);
        }
    }
    
    public static class EventIdServerCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            EventIdServerEndpoint endpoint = new EventIdServerEndpoint(WebSocketBehavior.SERVER.name());
            resp.setAcceptedSubProtocol("eventids");
            return endpoint;
        }
    }
    
    private static class EventIdServerEndpoint extends UntrustedWSEndpoint
    {
        public EventIdServerEndpoint(String id)
        {
            super(id);
        }
        
        @Override
        public void onWebSocketText(String text)
        {
            super.onWebSocketText(text);
            
            if (text.startsWith("seq|"))
            {
                String parts[] = text.split("\\|");
                int from = Integer.parseInt(parts[1]);
                int to = Integer.parseInt(parts[2]);
                
                session.getRemote().setBatchMode(BatchMode.OFF);
                
                for (int id = from; id < to; id++)
                {
                    session.getRemote().sendStringByFuture(Integer.toString(id));
                }
            }
        }
    }
    
    private UntrustedWSServer server;
    private WebSocketContainer client;
    
    @BeforeEach
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }
    
    @BeforeEach
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.start();
    }
    
    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testManyIds(TestInfo testInfo) throws Exception
    {
        server.registerWebSocket("/eventids", new EventIdServerCreator());
        
        URI wsUri = server.getWsUri().resolve("/eventids");
        EventIdSocket clientSocket = new EventIdSocket(testInfo);
        Session clientSession = client.connectToServer(clientSocket, wsUri);
        
        final int from = 1000;
        final int to = 2000;
        
        clientSession.getAsyncRemote().sendText("seq|" + from + "|" + to);
        
        // collect seen ids
        List<Integer> seen = new ArrayList<>();
        for (int i = from; i < to; i++)
        {
            // validate that ids don't repeat.
            EventId receivedId = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
            assertFalse(seen.contains(receivedId.eventId),"Already saw ID: " + receivedId.eventId);
            seen.add(receivedId.eventId);
        }
        
        // validate that all expected ids have been seen (order is irrelevant here)
        for (int expected = from; expected < to; expected++)
        {
            assertTrue(seen.contains(expected), "Has expected id:" + expected);
        }
    }
}
