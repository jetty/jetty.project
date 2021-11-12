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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.LogarithmicArrayByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketBufferPoolTest
{
    private static final Logger LOG = Log.getLogger(WebSocketBufferPoolTest.class);

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789{}\":;<>,.()[]".toCharArray();
    private static final AtomicReference<CountDownLatch> _latchReference = new AtomicReference<>();
    private Server _server;
    private ArrayByteBufferPool _bufferPool;
    private HttpClient _httpClient;
    private WebSocketClient _websocketClient;

    @WebSocket
    public static class ServerSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws InterruptedException
        {
            CountDownLatch latch = _latchReference.get();
            latch.countDown();
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            session.close(1000, "success");
        }
    }

    @WebSocket
    public static class ClientSocket
    {
        private int code;
        private String reason;
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("MessageSize: {}", message.length());
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }

        @OnWebSocketClose
        public void onClose(int code, String status)
        {
            this.code = code;
            this.reason = status;
            closeLatch.countDown();
        }
    }

    public String randomString(int len)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++)
        {
            sb.append(ALPHABET[(int)(Math.random() * ALPHABET.length)]);
        }
        return sb.toString();
    }

    @BeforeEach
    public void before() throws Exception
    {
        // Ensure the threadPool can handle more than 100 threads.
        QueuedThreadPool threadPool = new QueuedThreadPool(200);

        _server = new Server(threadPool);
        int maxMemory = 1024 * 1024 * 16;
        _bufferPool = new LogarithmicArrayByteBufferPool(-1, -1, -1, maxMemory, maxMemory);
        _bufferPool.setDetailedDump(true);
        _server.addBean(_bufferPool);

        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(8080);
        _server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        WebSocketUpgradeFilter.configure(contextHandler);
        NativeWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, configuration) ->
        {
            WebSocketPolicy policy = configuration.getPolicy();
            policy.setMaxTextMessageBufferSize(Integer.MAX_VALUE);
            policy.setMaxTextMessageSize(Integer.MAX_VALUE);
            configuration.addMapping("/websocket", ServerSocket.class);
        }));

        contextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                CountDownLatch countDownLatch = _latchReference.get();
                if (countDownLatch != null)
                    assertThat(countDownLatch.getCount(), is(0L));

                int numThreads = Integer.parseInt(req.getParameter("numThreads"));
                _latchReference.compareAndSet(countDownLatch, new CountDownLatch(numThreads));
            }
        }), "/setCount");

        _server.setHandler(contextHandler);
        _server.addBean(new MBeanContainer(ManagementFactory.getPlatformMBeanServer()));
        _server.start();

        _httpClient = new HttpClient();
        _httpClient.setByteBufferPool(new NullByteBufferPool());
        _websocketClient = new WebSocketClient(_httpClient);
        _websocketClient.start();

        // Check the bufferPool used for the server is now used in the websocket configuration.
        NativeWebSocketConfiguration config = (NativeWebSocketConfiguration)contextHandler.getAttribute(NativeWebSocketConfiguration.class.getName());
        assertNotNull(config);
        assertThat(config.getFactory().getBufferPool(), is(_bufferPool));
    }

    @AfterEach
    public void after() throws Exception
    {
        _websocketClient.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        int numThreads = 100;
        int maxMessageSize = 1024 * 64;
        for (int msgSize = 1024; msgSize < maxMessageSize; msgSize += 1024)
        {
            ContentResponse get = _httpClient.GET("http://localhost:8080/setCount?numThreads=" + numThreads);
            assertThat(get.getStatus(), is(200));

            Callback.Completable completable = new Callback.Completable()
            {
                final AtomicInteger count = new AtomicInteger(numThreads);

                @Override
                public void succeeded()
                {
                    if (count.decrementAndGet() == 0)
                        super.succeeded();
                }
            };

            int messageSize = msgSize;
            for (int i = 0; i < numThreads; i++)
            {
                new Thread(() ->
                {
                    try
                    {
                        ClientSocket clientSocket = new ClientSocket();
                        URI uri = URI.create("ws://localhost:8080/websocket");
                        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
                        upgradeRequest.addExtensions("permessage-deflate");
                        Session session = _websocketClient.connect(clientSocket, uri, upgradeRequest).get(5, TimeUnit.SECONDS);
                        assertTrue(session.getUpgradeResponse().getExtensions().stream().anyMatch(config -> config.getName().equals("permessage-deflate")));

                        session.getRemote().sendString(randomString(messageSize));
                        assertTrue(clientSocket.closeLatch.await(20, TimeUnit.SECONDS));
                        assertThat(clientSocket.code, is(1000));
                        assertThat(clientSocket.reason, is("success"));
                        completable.complete(null);
                    }
                    catch (Throwable t)
                    {
                        completable.failed(t);
                    }
                }).start();
            }

            completable.get(20, TimeUnit.SECONDS);
        }

        assertThat(_bufferPool.getDirectMemory(), lessThanOrEqualTo(_bufferPool.getMaxDirectMemory()));
        assertThat(_bufferPool.getHeapMemory(), lessThanOrEqualTo(_bufferPool.getMaxHeapMemory()));
    }
}
