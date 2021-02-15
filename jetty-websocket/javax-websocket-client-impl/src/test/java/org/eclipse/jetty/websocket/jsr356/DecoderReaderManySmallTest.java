//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Disabled("Not working atm")
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

    @ClientEndpoint(decoders = {EventIdDecoder.class})
    public static class EventIdSocket
    {
        public LinkedBlockingQueue<EventId> messageQueue = new LinkedBlockingQueue<>();
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @OnClose
        public void onClose(CloseReason close)
        {
            closeLatch.countDown();
        }

        @OnMessage
        public void onMessage(EventId msg)
        {
            messageQueue.offer(msg);
        }

        public void awaitClose() throws InterruptedException
        {
            closeLatch.await(4, TimeUnit.SECONDS);
        }
    }

    private static final Logger LOG = Log.getLogger(DecoderReaderManySmallTest.class);

    private static BlockheadServer server;
    private WebSocketContainer client;

    @BeforeEach
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        ((LifeCycle)client).stop();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testManyIds() throws Exception
    {
        // Hook into server connection creation
        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        EventIdSocket ids = new EventIdSocket();
        client.connectToServer(ids, server.getWsUri());

        final int from = 1000;
        final int to = 2000;

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Setup echo of frames on server side
            serverConn.setIncomingFrameConsumer((frame) ->
            {
                WebSocketFrame wsFrame = (WebSocketFrame)frame;
                if (wsFrame.getOpCode() == OpCode.TEXT)
                {
                    String msg = wsFrame.getPayloadAsUTF8();
                    if (msg == "generate")
                    {
                        for (int id = from; id < to; id++)
                        {
                            TextFrame event = new TextFrame();
                            event.setPayload(Integer.toString(id));
                            serverConn.write(event);
                        }
                        serverConn.write(new CloseInfo(StatusCode.NORMAL).asFrame());
                    }
                }
            });

            int count = from - to;
            ids.awaitClose();
            // collect seen ids
            List<Integer> seen = new ArrayList<>();
            for (int i = 0; i < count; i++)
            {
                EventId id = ids.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                // validate that ids don't repeat.
                assertFalse(seen.contains(id.eventId), "Already saw ID: " + id.eventId);
                seen.add(id.eventId);
            }

            // validate that all expected ids have been seen (order is irrelevant here)
            for (int expected = from; expected < to; expected++)
            {
                assertThat("Has expected id:" + expected, expected, is(in(seen)));
            }
        }
    }
}
