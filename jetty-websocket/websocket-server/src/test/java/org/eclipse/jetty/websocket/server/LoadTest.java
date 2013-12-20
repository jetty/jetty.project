//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("Need to rewrite this")
public class LoadTest
{
    private final static int loops = 50;
    
    @SuppressWarnings("serial")
    public static class LoadServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(LoadSocket.class);
        }
    }

    @WebSocket
    public static class LoadSocket
    {
        private Session session;
        public static AtomicLong count = new AtomicLong(0);

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
        }

        @OnWebSocketMessage
        public void onWebSocketText(String message)
        {
            session.getRemote().sendString(message,null);
            long iter = count.incrementAndGet();
            if ((iter % 100) == 0)
            {
                LOG.info("Echo'd back {} msgs",iter);
            }
        }
    }

    /**
     * Thread to just send a mess of text messages.
     */
    public static class TextGen implements Runnable
    {
        private final BlockheadClient client;
        private final int iterations;

        public TextGen(BlockheadClient client, int iterations)
        {
            this.client = client;
            this.iterations = iterations;
        }

        @Override
        public void run()
        {
            try
            {
                for (int i = 0; i < iterations; i++)
                {
                    client.write(new TextFrame().setPayload("msg-" + i));
                    if ((i % 100) == 0)
                    {
                        LOG.info("Client Wrote {} msgs",i);
                    }
                }
                LOG.info("Wrote {} msgs",iterations);
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }
    }

    private static final Logger LOG = Log.getLogger(LoadTest.class);
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new LoadServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testA() throws Exception
    {
        for (int i=0;i<loops;i++)
            testManyMessages();
    }
    
    @Test
    public void testB() throws Exception
    {
        for (int i=0;i<loops;i++)
            testManyMessages();
    }
    
    @Test
    public void testC() throws Exception
    {
        for (int i=0;i<loops;i++)
            testManyMessages();
    }
    
    @Test
    public void testD() throws Exception
    {
        for (int i=0;i<loops;i++)
            testManyMessages();
    }
    
    @Test
    public void testE() throws Exception
    {
        for (int i=0;i<loops;i++)
            testManyMessages();
    }
    
    @Test
    public void testManyMessages() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            int iterations = 10000;

            LoadSocket.count.set(0);

            threadPool.execute(new TextGen(client,iterations));

            client.readFrames(iterations,TimeUnit.SECONDS,40);
        }
        finally
        {
            client.close(StatusCode.NORMAL,"All Done");
        }
    }
}
