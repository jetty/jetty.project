//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer.ServerConnection;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.junit.runner.RunWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@RunWith(AdvancedRunner.class)
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

    @Test(expected = UpgradeException.class)
    public void testBadHandshake() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        URI wsUri = server.getWsUri();
        FutureCallback<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection connection = server.accept();
        connection.readRequest();
        // no upgrade, just fail with a 404 error
        connection.respond("HTTP/1.1 404 NOT FOUND\r\n\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    @Test(expected = UpgradeException.class)
    public void testBadUpgrade() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        URI wsUri = server.getWsUri();
        FutureCallback<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection connection = server.accept();
        connection.readRequest();
        // Upgrade badly
        connection.respond("HTTP/1.1 101 Upgrade\r\n" + "Sec-WebSocket-Accept: rubbish\r\n" + "\r\n");

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> UpgradeException");
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    @Test
    public void testBasicEcho_FromClient() throws Exception
    {
        TrackingSocket cliSock = new TrackingSocket();

        WebSocketClient client = factory.newWebSocketClient(cliSock);
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = server.getWsUri();
        UpgradeRequest request = client.getUpgradeRequest();
        request.setSubProtocols("echo");
        Future<UpgradeResponse> future = client.connect(wsUri);

        final ServerConnection srvSock = server.accept();
        srvSock.upgrade();

        UpgradeResponse resp = future.get(500,TimeUnit.MILLISECONDS);
        Assert.assertThat("Response",resp,notNullValue());
        Assert.assertThat("Response.success",resp.isSuccess(),is(true));

        cliSock.assertWasOpened();
        cliSock.assertNotClosed();

        Assert.assertThat("Factory.sockets.size",factory.getConnectionManager().getClients().size(),is(1));

        cliSock.getConnection().write(null,new FutureCallback<Void>(),"Hello World!");
        srvSock.echoMessage(1,TimeUnit.MILLISECONDS,500);
        // wait for response from server
        cliSock.waitForMessage(TimeUnit.MILLISECONDS,500);

        cliSock.assertMessage("Hello World!");
    }

    @Test
    public void testBasicEcho_FromServer() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);
        Future<UpgradeResponse> future = client.connect(server.getWsUri());

        // Server
        final ServerConnection srvSock = server.accept();
        srvSock.upgrade();

        // Validate connect
        UpgradeResponse resp = future.get(500,TimeUnit.MILLISECONDS);
        Assert.assertThat("Response",resp,notNullValue());
        Assert.assertThat("Response.success",resp.isSuccess(),is(true));

        // Have server send initial message
        srvSock.write(WebSocketFrame.text("Hello World"));

        // Verify connect
        future.get(500,TimeUnit.MILLISECONDS);
        wsocket.assertWasOpened();
        wsocket.awaitMessage(1,TimeUnit.MILLISECONDS,500);

        wsocket.assertMessage("Hello World");
    }

    @Test
    public void testBlockReceiving() throws Exception
    {
        final Exchanger<String> exchanger = new Exchanger<String>();
        TrackingSocket tsocket = new TrackingSocket();
        tsocket.messageExchanger = exchanger;
        WebSocketClient client = factory.newWebSocketClient(tsocket);
        client.getPolicy().setIdleTimeout(60000);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection sconnection = server.accept();
        sconnection.setSoTimeout(60000);
        sconnection.upgrade();

        future.get(500,TimeUnit.MILLISECONDS);

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
        sconnection.close(StatusCode.NORMAL);

        Assert.assertTrue("Client Socket Closed",tsocket.closeLatch.await(10,TimeUnit.SECONDS));
        tsocket.assertCloseCode(StatusCode.NORMAL);
    }

    @Test
    @Ignore("Not working, it hangs")
    public void testBlockSending() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        final ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        future.get(250,TimeUnit.MILLISECONDS);

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
            wsocket.getConnection().write(null,new FutureCallback<Void>(),mesg);
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
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        // Intentionally not accept incoming socket.
        // server.accept();

        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Should have Timed Out");
        }
        catch (TimeoutException e)
        {
            // Expected Path
            wsocket.assertNotOpened();
        }
    }

    @Test(expected = ConnectException.class)
    @Ignore("Needs work in SelectManager to support this use case")
    public void testConnectionRefused() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        // Intentionally bad port
        URI wsUri = new URI("ws://127.0.0.1:1");
        Future<UpgradeResponse> future = client.connect(wsUri);

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(1000,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> ConnectException");
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    @Test(expected = TimeoutException.class)
    public void testConnectionTimeout() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection ssocket = server.accept();
        Assert.assertNotNull(ssocket);
        // Intentionally don't upgrade
        // ssocket.upgrade();

        // The attempt to get upgrade response future should throw error
        try
        {
            future.get(500,TimeUnit.MILLISECONDS);
            Assert.fail("Expected ExecutionException -> TimeoutException");
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    @Test
    @Ignore("Work In Progress")
    public void testIdle() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        WebSocketClient client = factory.newWebSocketClient(wsocket);
        client.getPolicy().setIdleTimeout(500);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        future.get(250,TimeUnit.MILLISECONDS);

        long start = System.currentTimeMillis();
        wsocket.closeLatch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue((System.currentTimeMillis() - start) < 5000);
        wsocket.assertCloseCode(StatusCode.NORMAL);
    }

    @Test
    public void testMessageBiggerThanBufferSize() throws Exception
    {
        WebSocketClientFactory factSmall = new WebSocketClientFactory();
        factSmall.start();
        try
        {
            int bufferSize = 512;
            factSmall.getPolicy().setBufferSize(512);

            TrackingSocket wsocket = new TrackingSocket();
            WebSocketClient client = factSmall.newWebSocketClient(wsocket);

            URI wsUri = server.getWsUri();
            Future<UpgradeResponse> future = client.connect(wsUri);

            ServerConnection ssocket = server.accept();
            ssocket.upgrade();

            future.get(500,TimeUnit.MILLISECONDS);

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
        finally
        {
            factSmall.stop();
        }
    }

    @Test
    @Ignore("Work In Progress")
    public void testNotIdle() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();

        WebSocketClient client = factory.newWebSocketClient(wsocket);
        client.getPolicy().setIdleTimeout(500);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        future.get(250,TimeUnit.MILLISECONDS);

        wsocket.assertIsOpen();

        // Send some messages from client to server
        byte[] recv = new byte[1024];
        int len = -1;
        for (int i = 0; i < 10; i++)
        {
            Thread.sleep(250);
            wsocket.getConnection().write(null,new FutureCallback<Void>(),"Hello");
            len = ssocket.getInputStream().read(recv,0,recv.length);
            Assert.assertTrue(len > 0);
        }

        // Send some messages from server to client
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
        long dur = (System.currentTimeMillis() - start);
        Assert.assertThat("Overall duration",dur,lessThanOrEqualTo(5000L));
        wsocket.assertClose(StatusCode.PROTOCOL,"Invalid close code 1111");
    }

    @Test
    @Ignore("Test for is-open is broken")
    public void testUpgradeThenTCPClose() throws Exception
    {
        TrackingSocket wsocket = new TrackingSocket();
        WebSocketClient client = factory.newWebSocketClient(wsocket);

        URI wsUri = server.getWsUri();
        Future<UpgradeResponse> future = client.connect(wsUri);

        ServerConnection ssocket = server.accept();
        ssocket.upgrade();

        future.get(500,TimeUnit.MILLISECONDS);

        wsocket.assertIsOpen();

        ssocket.disconnect();

        Assert.assertThat("Close should have been detected",wsocket.closeLatch.await(10,TimeUnit.SECONDS),is(true));
    }
}
