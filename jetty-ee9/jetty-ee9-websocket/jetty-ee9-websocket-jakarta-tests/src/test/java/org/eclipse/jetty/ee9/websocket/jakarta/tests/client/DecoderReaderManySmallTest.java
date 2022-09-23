//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.CoreServer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSEventTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.internal.MessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DecoderReaderManySmallTest
{
    private CoreServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new CoreServer((req, resp, cb) ->
        {
            List<String> offeredSubProtocols = req.getSubProtocols();
            if (!offeredSubProtocols.isEmpty())
                resp.setAcceptedSubProtocol(offeredSubProtocols.get(0));
            return new EventIdFrameHandler();
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
    public void testManyIds() throws Exception
    {
        final int from = 1000;
        final int to = 2000;

        EventIdSocket clientSocket = new EventIdSocket();
        try (Session clientSession = client.connectToServer(clientSocket, server.getWsUri()))
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
