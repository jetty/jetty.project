//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.client;

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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.MessageHandler;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.javax.tests.CoreServer;
import org.eclipse.jetty.websocket.javax.tests.WSEventTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DecoderReaderManySmallTest
{
    private CoreServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new CoreServer(new CoreServer.BaseNegotiator()
        {
            @Override
            public FrameHandler negotiate(Negotiation negotiation) throws IOException
            {
                List<String> offeredSubProtocols = negotiation.getOfferedSubprotocols();

                if (!offeredSubProtocols.isEmpty())
                {
                    negotiation.setSubprotocol(offeredSubProtocols.get(0));
                }

                return new EventIdFrameHandler();
            }
        });
        server.start();

        client = ContainerProvider.getWebSocketContainer();
        server.addBean(client, true); // allow client to stop with server
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testManyIds(TestInfo testInfo) throws Exception
    {
        URI wsUri = server.getWsUri().resolve("/eventids");
        EventIdSocket clientSocket = new EventIdSocket(testInfo.getTestMethod().toString());

        final int from = 1000;
        final int to = 2000;

        try (Session clientSession = client.connectToServer(clientSocket, wsUri))
        {
            clientSession.getAsyncRemote().sendText("seq|" + from + "|" + to);
        }

        // collect seen ids
        List<Integer> seen = new ArrayList<>();
        for (int i = from; i < to; i++)
        {
            // validate that ids don't repeat.
            EventId receivedId = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
            assertFalse(seen.contains(receivedId.eventId), "Already saw ID: " + receivedId.eventId);
            seen.add(receivedId.eventId);
        }

        // validate that all expected ids have been seen (order is irrelevant here)
        for (int expected = from; expected < to; expected++)
        {
            assertTrue(seen.contains(expected), "Has expected id:" + expected);
        }
    }

    public static class EventId
    {
        public int eventId;
    }

    public static class EventIdDecoder implements Decoder.TextStream<EventId>
    {
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

        @Override
        public void destroy()
        {
        }

        @Override
        public void init(EndpointConfig config)
        {
        }
    }

    @ClientEndpoint(decoders = EventIdDecoder.class, subprotocols = "eventids")
    public static class EventIdSocket extends WSEventTracker
    {
        public BlockingQueue<EventId> messageQueue = new LinkedBlockingDeque<>();

        public EventIdSocket(String id)
        {
            super(id);
        }

        @SuppressWarnings("unused")
        @OnMessage
        public void onMessage(EventId msg)
        {
            messageQueue.offer(msg);
        }
    }

    public static class EventIdFrameHandler extends MessageHandler
    {
        @Override
        public void onText(String text, Callback callback)
        {
            if (text.startsWith("seq|"))
            {
                String[] parts = text.split("\\|");
                int from = Integer.parseInt(parts[1]);
                int to = Integer.parseInt(parts[2]);

                for (int id = from; id < to; id++)
                {
                    sendText(Integer.toString(id), Callback.NOOP, false);
                }
            }

            getCoreSession().flush(callback);
        }
    }
}
