//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CountDownLatch;
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

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore("Not working atm")
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

    @ClientEndpoint(decoders = { EventIdDecoder.class })
    public static class EventIdSocket
    {
        public EventQueue<EventId> messageQueue = new EventQueue<>();
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @OnClose
        public void onClose(CloseReason close)
        {
            closeLatch.countDown();
        }

        @OnMessage
        public void onMessage(EventId msg)
        {
            messageQueue.add(msg);
        }

        public void awaitClose() throws InterruptedException
        {
            closeLatch.await(4,TimeUnit.SECONDS);
        }
    }

    private static class EventIdServer implements Runnable
    {
        private BlockheadServer server;
        private IBlockheadServerConnection sconnection;
        private CountDownLatch connectLatch = new CountDownLatch(1);

        public EventIdServer(BlockheadServer server)
        {
            this.server = server;
        }

        @Override
        public void run()
        {
            try
            {
                sconnection = server.accept();
                sconnection.setSoTimeout(60000);
                sconnection.upgrade();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
            finally
            {
                connectLatch.countDown();
            }
        }

        public void writeSequentialIds(int from, int to) throws IOException
        {
            for (int id = from; id < to; id++)
            {
                TextFrame frame = new TextFrame();
                frame.setPayload(Integer.toString(id));
                sconnection.write(frame);
            }
        }

        public void close() throws IOException
        {
            sconnection.close();
        }

        public void awaitConnect() throws InterruptedException
        {
            connectLatch.await(1,TimeUnit.SECONDS);
        }
    }

    private static final Logger LOG = Log.getLogger(DecoderReaderManySmallTest.class);

    @Rule
    public TestTracker tt = new TestTracker();

    private BlockheadServer server;
    private WebSocketContainer client;

    @Before
    public void initClient()
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testManyIds() throws Exception
    {
        EventIdSocket ids = new EventIdSocket();
        EventIdServer idserver = new EventIdServer(server);
        new Thread(idserver).start();
        client.connectToServer(ids,server.getWsUri());
        idserver.awaitConnect();
        int from = 1000;
        int to = 2000;
        idserver.writeSequentialIds(from,to);
        idserver.close();
        int count = from - to;
        ids.messageQueue.awaitEventCount(count,4,TimeUnit.SECONDS);
        ids.awaitClose();
        // collect seen ids
        List<Integer> seen = new ArrayList<>();
        for(EventId id: ids.messageQueue)
        {
            // validate that ids don't repeat.
            Assert.assertFalse("Already saw ID: " + id.eventId, seen.contains(id.eventId));
            seen.add(id.eventId);
        }
        
        // validate that all expected ids have been seen (order is irrelevant here)
        for(int expected=from; expected<to; expected++)
        {
            Assert.assertTrue("Has expected id:"+expected,seen.contains(expected));
        }
    }
}
