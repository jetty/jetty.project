// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.server.examples.MyEchoSocket;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class WebSocketOverSSLTest
{
    // Fire and Forget callback
    public static Callback<Void> fnf()
    {
        return new FutureCallback<Void>();
    }

    private Server _server;
    private int _port;
    private QueuedThreadPool _threadPool;

    // private WebSocketClientFactory _wsFactory;
    private WebSocketConnection _connection;

    @After
    public void destroy() throws Exception
    {
        if (_connection != null)
        {
            _connection.close();
        }

        // if (_wsFactory != null)
        // _wsFactory.stop();

        if (_threadPool != null)
        {
            _threadPool.stop();
        }

        if (_server != null)
        {
            _server.stop();
            _server.join();
        }
    }

    private void startClient(final Object webSocket) throws Exception
    {
        Assert.assertTrue(_server.isStarted());

        _threadPool = new QueuedThreadPool();
        _threadPool.setName("wsc-" + _threadPool.getName());
        _threadPool.start();

        // _wsFactory = new WebSocketClientFactory(_threadPool, new ZeroMasker());
        // SslContextFactory cf = _wsFactory.getSslContextFactory();
        // cf.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        // cf.setKeyStorePassword("storepwd");
        // cf.setKeyManagerPassword("keypwd");
        // _wsFactory.start();

        // WebSocketClient client = new WebSocketClient(_wsFactory);
        // _connection = client.open(new URI("wss://localhost:" + _port), webSocket).get(5, TimeUnit.SECONDS);
    }

    private void startServer(final Object websocket) throws Exception
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        _server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector(_server, sslContextFactory);
        _server.addConnector(connector);
        _server.setHandler(new WebSocketHandler.Simple(websocket.getClass()));
        _server.start();
        _port = connector.getLocalPort();
    }

    @Test
    @Ignore("SSL Not yet implemented")
    public void testManyMessages() throws Exception
    {
        startServer(MyEchoSocket.class);
        int count = 1000;
        final CountDownLatch clientLatch = new CountDownLatch(count);
        startClient(new WebSocketAdapter()
        {
            @Override
            public void onWebSocketText(String message)
            {
                clientLatch.countDown();
            }
        });

        char[] chars = new char[256];
        Arrays.fill(chars,'x');
        String message = new String(chars);
        for (int i = 0; i < count; ++i)
        {
            _connection.write(null,fnf(),message);
        }

        Assert.assertTrue(clientLatch.await(20,TimeUnit.SECONDS));

        // While messages may have all arrived, the SSL close alert
        // may be in the way so give some time for it to be processed.
        TimeUnit.SECONDS.sleep(1);
    }

    @Test
    @Ignore("SSL Not yet implemented")
    public void testWebSocketOverSSL() throws Exception
    {
        final String message = "message";
        final CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new WebSocketAdapter()
        {
            private WebSocketConnection connection;

            @Override
            public void onWebSocketConnect(WebSocketConnection connection)
            {
                this.connection = connection;
            }

            @Override
            public void onWebSocketText(String message)
            {
                try
                {
                    Assert.assertEquals(message,message);
                    connection.write(null,fnf(),message);
                    serverLatch.countDown();
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                }
            }
        });
        final CountDownLatch clientLatch = new CountDownLatch(1);
        startClient(new WebSocketAdapter()
        {
            @Override
            public void onWebSocketText(String data)
            {
                Assert.assertEquals(message,data);
                clientLatch.countDown();
            }
        });
        _connection.write(null,fnf(),message);

        Assert.assertTrue(serverLatch.await(5,TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5,TimeUnit.SECONDS));
    }
}
