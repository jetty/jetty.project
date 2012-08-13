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
package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer.ServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WebSocketClientTest
{
    private BlockheadServer server;
    private WebSocketClientFactory factory;

    @Before
    public void startFactory() throws Exception
    {
        factory = new WebSocketClientFactory();
        factory.start();
    }

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopFactory() throws Exception
    {
        factory.stop();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testBadHandshake() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection connection = server.accept();
        String req = connection.readRequest();
        // no upgrade, just fail with a 404 error
        connection.respond("HTTP/1.1 404 NOT FOUND\r\n\r\n");

        Throwable error = null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail("Should have resulted in an ExecutionException -> IOException");
        }
        catch (ExecutionException e)
        {
            error = e.getCause();
        }

        wsocket.assertNotOpened();
        wsocket.assertCloseCode(StatusCode.PROTOCOL);
        Assert.assertTrue(error instanceof IOException);
        Assert.assertTrue(error.getMessage().indexOf("404 NOT FOUND") > 0);

    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection connection = server.accept();
        connection.respond("HTTP/1.1 101 Upgrade\r\n" + "Sec-WebSocket-Accept: rubbish\r\n" + "\r\n");

        Throwable error = null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            error = e.getCause();
        }

        wsocket.assertNotOpened();
        wsocket.assertCloseCode(StatusCode.PROTOCOL);
        Assert.assertTrue(error instanceof IOException);
        Assert.assertThat("Error Message",error.getMessage(),containsString("Bad Sec-WebSocket-Accept"));
    }

    @Test
    public void testBadURL() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();
        try
        {
            // Intentionally bad scheme in URI
            URI wsUri = new URI("http://localhost:8080");

            client.connect(wsUri,wsocket); // should toss exception

            Assert.fail("Expected IllegalArgumentException");
        }
        catch (IllegalArgumentException e)
        {
            // expected path
            wsocket.assertNotOpened();
        }
    }

    @Test
    public void testBlockReceiving() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();
        client.getPolicy().setIdleTimeout(60000);

        final AtomicBoolean open = new AtomicBoolean(false);
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        final StringBuilder closeMessage = new StringBuilder();
        final Exchanger<String> exchanger = new Exchanger<String>();

        WebSocketListener socket = new WebSocketAdapter()
        {
            @Override
            public void onWebSocketClose(int statusCode, String reason)
            {
                close.set(statusCode);
                closeMessage.append(reason);
                _latch.countDown();
            }

            @Override
            public void onWebSocketConnect(WebSocketConnection connection)
            {
                open.set(true);
            }

            @Override
            public void onWebSocketText(String message)
            {
                try
                {
                    exchanger.exchange(message);
                }
                catch (InterruptedException e)
                {
                    // e.printStackTrace();
                }
            }
        };

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,socket);

        ServerConnection sconnection = server.accept();
        sconnection.setSoTimeout(60000);

        WebSocketConnection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());

        // define some messages to send server to client
        byte[] send = new byte[]
        { (byte)0x81, (byte)0x05, (byte)'H', (byte)'e', (byte)'l', (byte)'l', (byte)'o' };
        final int messages = 100000;
        final AtomicInteger m = new AtomicInteger();

        // Set up a consumer of received messages that waits a while before consuming
        Thread consumer = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(200);
                    while (m.get() < messages)
                    {
                        String msg = exchanger.exchange(null);
                        if ("Hello".equals(msg))
                        {
                            m.incrementAndGet();
                        }
                        else
                        {
                            throw new IllegalStateException("exchanged " + msg);
                        }
                        if ((m.get() % 1000) == 0)
                        {
                            // Artificially slow reader
                            Thread.sleep(10);
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    return;
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        consumer.start();

        long start = System.currentTimeMillis();
        for (int i = 0; i < messages; i++)
        {
            sconnection.write(send,0,send.length);
            sconnection.flush();
        }

        while (consumer.isAlive())
        {
            Thread.sleep(10);
        }

        // Duration of the read operation.
        long readDur = (System.currentTimeMillis() - start);

        Assert.assertThat("read duration",readDur,greaterThan(1000L)); // reading was blocked
        Assert.assertEquals(m.get(),messages);

        // Close with code
        start = System.currentTimeMillis();
        sconnection.write(new byte[]
        { (byte)0x88, (byte)0x02, (byte)4, (byte)87 },0,4);
        sconnection.flush();

        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue((System.currentTimeMillis() - start) < 5000);
        Assert.assertEquals(1002,close.get());
        Assert.assertEquals("Invalid close code 1111",closeMessage.toString());
    }

    @Test
    public void testBlockSending() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();
        client.getPolicy().setIdleTimeout(10000);

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        final ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        WebSocketConnection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        wsocket.assertWasOpened();
        wsocket.assertNotClosed();

        final int messages = 200000;
        final AtomicLong totalB = new AtomicLong();

        Thread consumer = new Thread()
        {
            @Override
            public void run()
            {
                // Thread.sleep is for artificially poor performance reader needed for this testcase.
                try
                {
                    Thread.sleep(200);
                    byte[] recv = new byte[32 * 1024];

                    int len = 0;
                    while (len >= 0)
                    {
                        totalB.addAndGet(len);
                        len = ssocket.getInputStream().read(recv,0,recv.length);
                        Thread.sleep(10);
                    }
                }
                catch (InterruptedException e)
                {
                    return;
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        consumer.start();

        // Send lots of messages client to server
        long start = System.currentTimeMillis();
        String mesg = "This is a test message to send";
        for (int i = 0; i < messages; i++)
        {
            connection.write(null,new FutureCallback<Void>(),mesg);
        }

        // Duration for the write phase
        long writeDur = (System.currentTimeMillis() - start);

        // wait for consumer to complete
        while (totalB.get() < (messages * (mesg.length() + 6L)))
        {
            Thread.sleep(10);
        }

        Assert.assertThat("write duration",writeDur,greaterThan(1000L)); // writing was blocked
        Assert.assertEquals(messages * (mesg.length() + 6L),totalB.get());

        consumer.interrupt();
    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        // Intentionally not accept incoming socket.
        // server.accept();

        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail("Should have Timed Out");
        }
        catch (TimeoutException e)
        {
            // Expected Path
            wsocket.assertNotOpened();
            wsocket.assertCloseCode(StatusCode.NO_CLOSE);
        }
    }

    @Test
    public void testConnectionRefused() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        // Intentionally bad port
        URI wsUri = new URI("ws://127.0.0.1:1");
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        Throwable error = null;
        try
        {
            future.get(1,TimeUnit.SECONDS);
            Assert.fail("Expected ExecutionException");
        }
        catch (ExecutionException e)
        {
            error = e.getCause();
        }

        wsocket.assertNotOpened();
        wsocket.assertCloseCode(StatusCode.NO_CLOSE);
        Assert.assertTrue(error instanceof ConnectException);
    }

    @Test
    public void testConnectionTimeout() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection ssocket = server.accept();
        Assert.assertNotNull(ssocket);
        // Intentionally don't upgrade
        // ssocket.upgrade();

        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail("Expected Timeout Exception");
        }
        catch (TimeoutException e)
        {
            // Expected path
            wsocket.assertNotOpened();
            wsocket.assertCloseCode(StatusCode.NO_CLOSE);
        }
    }

    @Test
    public void testIdle() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();
        client.getPolicy().setIdleTimeout(500);

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        WebSocketConnection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        wsocket.assertWasOpened();
        wsocket.assertNotClosed();

        long start = System.currentTimeMillis();
        wsocket.closeLatch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue((System.currentTimeMillis() - start) < 5000);
        wsocket.assertCloseCode(StatusCode.NORMAL);
    }

    @Test
    public void testMessageBiggerThanBufferSize() throws Exception
    {
        int bufferSize = 512;
        factory.getPolicy().setBufferSize(512);
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

        int length = bufferSize + (bufferSize / 2); // 1.5 times buffer size
        ssocket.write(0x80 | 0x01); // FIN + TEXT
        ssocket.write(0x7E); // No MASK and 2 bytes length
        ssocket.write(length >> 8); // first length byte
        ssocket.write(length & 0xFF); // second length byte
        for (int i = 0; i < length; ++i)
        {
            ssocket.write('x');
        }
        ssocket.flush();

        Assert.assertTrue(wsocket.dataLatch.await(1000,TimeUnit.SECONDS));
    }

    @Test
    public void testNotIdle() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();
        client.getPolicy().setIdleTimeout(500);

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        WebSocketConnection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);

        wsocket.assertIsOpen();

        // Send some messages client to server
        byte[] recv = new byte[1024];
        int len = -1;
        for (int i = 0; i < 10; i++)
        {
            Thread.sleep(250);
            connection.write(null,new FutureCallback<Void>(),"Hello");
            len = ssocket.getInputStream().read(recv,0,recv.length);
            Assert.assertTrue(len > 0);
        }

        // Send some messages server to client
        byte[] send = new byte[]
        { (byte)0x81, (byte)0x02, (byte)'H', (byte)'i' };

        for (int i = 0; i < 10; i++)
        {
            Thread.sleep(250);
            ssocket.write(send,0,send.length);
            ssocket.flush();
            Assert.assertEquals("Hi",wsocket.messageQueue.poll(1,TimeUnit.SECONDS));
        }

        // Close with code
        long start = System.currentTimeMillis();
        ssocket.write(new byte[]
        { (byte)0x88, (byte)0x02, (byte)4, (byte)87 },0,4);
        ssocket.flush();

        wsocket.closeLatch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue((System.currentTimeMillis() - start) < 5000);
        wsocket.assertClose(StatusCode.PROTOCOL,"Invalid close code 1111");
    }

    @Test
    public void testUpgradeThenTCPClose() throws Exception
    {
        WebSocketClient client = factory.newWebSocketClient();

        TrackingSocket wsocket = new TrackingSocket();

        URI wsUri = server.getWsUri();
        Future<WebSocketConnection> future = client.connect(wsUri,wsocket);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        WebSocketConnection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);

        wsocket.assertIsOpen();

        ssocket.close();
        wsocket.openLatch.await(10,TimeUnit.SECONDS);

        wsocket.assertCloseCode(StatusCode.NO_CLOSE);
    }

    @Test
    public void testURIWithDefaultPort() throws Exception
    {
        URI uri = new URI("ws://localhost");

        InetSocketAddress addr = WebSocketClient.toSocketAddress(uri);
        Assert.assertThat("URI (" + uri + ").host",addr.getHostName(),is("localhost"));
        Assert.assertThat("URI (" + uri + ").port",addr.getPort(),is(80));
    }

    @Test
    public void testURIWithDefaultWSSPort() throws Exception
    {
        URI uri = new URI("wss://localhost");
        InetSocketAddress addr = WebSocketClient.toSocketAddress(uri);
        Assert.assertThat("URI (" + uri + ").host",addr.getHostName(),is("localhost"));
        Assert.assertThat("URI (" + uri + ").port",addr.getPort(),is(443));
    }
}
