/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class WebSocketClientTest
{
    private WebSocketClientFactory _factory = new WebSocketClientFactory();
    private ServerSocket _server;
    private int _serverPort;

    @Before
    public void startServer() throws Exception
    {
        _server = new ServerSocket();
        _server.bind(null);
        _serverPort = _server.getLocalPort();
        _factory.start();
    }

    @After
    public void stopServer() throws Exception
    {
        if(_server != null) {
            _server.close();
        }
        _factory.stop();
    }

    @Test
    public void testMessageBiggerThanBufferSize() throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        int bufferSize = 512;
        WebSocketClientFactory factory = new WebSocketClientFactory(threadPool, new ZeroMaskGen(), bufferSize);
        threadPool.start();
        factory.start();
        WebSocketClient client = new WebSocketClient(factory);

        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        WebSocket.OnTextMessage websocket = new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
                openLatch.countDown();
            }

            public void onMessage(String data)
            {
                // System.out.println("data = " + data);
                dataLatch.countDown();
            }

            public void onClose(int closeCode, String message)
            {
            }
        };
        client.open(new URI("ws://127.0.0.1:" + _serverPort + "/"), websocket);

        Socket socket = _server.accept();
        accept(socket);

        Assert.assertTrue(openLatch.await(1, TimeUnit.SECONDS));
        OutputStream serverOutput = socket.getOutputStream();

        int length = bufferSize + bufferSize / 2;
        serverOutput.write(0x80 | 0x01); // FIN + TEXT
        serverOutput.write(0x7E); // No MASK and 2 bytes length
        serverOutput.write(length >> 8); // first length byte
        serverOutput.write(length & 0xFF); // second length byte
        for (int i = 0; i < length; ++i)
            serverOutput.write('x');
        serverOutput.flush();

        Assert.assertTrue(dataLatch.await(1000, TimeUnit.SECONDS));

        factory.stop();
        threadPool.stop();
    }

    @Test
    public void testBadURL() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        boolean bad=false;
        final AtomicBoolean open = new AtomicBoolean();
        try
        {
            client.open(new URI("http://localhost:8080"),new WebSocket()
            {
                public void onOpen(Connection connection)
                {
                    open.set(true);
                }

                public void onClose(int closeCode, String message)
                {}
            });

            Assert.fail();
        }
        catch(IllegalArgumentException e)
        {
            bad=true;
        }
        Assert.assertTrue(bad);
        Assert.assertFalse(open.get());
    }

    @Test
    public void testAsyncConnectionRefused() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();

        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:1"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Throwable error=null;
        try
        {
            future.get(1,TimeUnit.SECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }

        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,close.get());
        Assert.assertTrue(error instanceof ConnectException);

    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });


        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(TimeoutException e)
        {
            error=e;
        }

        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,close.get());
        Assert.assertTrue(error instanceof TimeoutException);

    }

    @Test
    public void testConnectionTimeout() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Assert.assertNotNull(_server.accept());

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(TimeoutException e)
        {
            error=e;
        }

        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,close.get());
        Assert.assertTrue(error instanceof TimeoutException);

    }

    @Test
    public void testBadHandshake() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Socket connection = _server.accept();
        respondToClient(connection, "HTTP/1.1 404 NOT FOUND\r\n\r\n");

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }

        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_PROTOCOL,close.get());
        Assert.assertTrue(error instanceof IOException);
        Assert.assertTrue(error.getMessage().indexOf("404 NOT FOUND")>0);

    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
            }
        });

        Socket connection = _server.accept();
        respondToClient(connection,
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: rubbish\r\n" +
                "\r\n" );

        Throwable error=null;
        try
        {
            future.get(250,TimeUnit.MILLISECONDS);
            Assert.fail();
        }
        catch(ExecutionException e)
        {
            error=e.getCause();
        }
        Assert.assertFalse(open.get());
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_PROTOCOL,close.get());
        Assert.assertTrue(error instanceof IOException);
        Assert.assertTrue(error.getMessage().indexOf("Bad Sec-WebSocket-Accept")>=0);
    }

    @Test
    public void testUpgradeThenTCPClose() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }
        });

        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());

        socket.close();
        _latch.await(10,TimeUnit.SECONDS);

        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,close.get());

    }

    @Test
    public void testIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);
        client.setMaxIdleTime(500);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }
        });

        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());

        long start=System.currentTimeMillis();
        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        Assert.assertEquals(WebSocketConnectionRFC6455.CLOSE_NORMAL,close.get());
    }

    @Test
    public void testNotIdle() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);
        client.setMaxIdleTime(500);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        final BlockingQueue<String> queue = new BlockingArrayQueue<String>();
        final StringBuilder closeMessage = new StringBuilder();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                closeMessage.append(message);
                _latch.countDown();
            }

            public void onMessage(String data)
            {
                queue.add(data);
            }
        });

        Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());



        // Send some messages client to server
        byte[] recv = new byte[1024];
        int len=-1;
        for (int i=0;i<10;i++)
        {
            Thread.sleep(250);
            connection.sendMessage("Hello");
            len=socket.getInputStream().read(recv,0,recv.length);
            Assert.assertTrue(len>0);
        }

        // Send some messages server to client
        byte[] send = new byte[] { (byte)0x81, (byte) 0x02, (byte)'H', (byte)'i'};

        for (int i=0;i<10;i++)
        {
            Thread.sleep(250);
            socket.getOutputStream().write(send,0,send.length);
            socket.getOutputStream().flush();
            Assert.assertEquals("Hi",queue.poll(1,TimeUnit.SECONDS));
        }

        // Close with code
        long start=System.currentTimeMillis();
        socket.getOutputStream().write(new byte[]{(byte)0x88, (byte) 0x02, (byte)4, (byte)87 },0,4);
        socket.getOutputStream().flush();

        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        Assert.assertEquals(1002,close.get());
        Assert.assertEquals("Invalid close code 1111", closeMessage.toString());
    }

    @Test
    public void testBlockSending() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);
        client.setMaxIdleTime(10000);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                _latch.countDown();
            }

            public void onMessage(String data)
            {
            }
        });

        final Socket socket = _server.accept();
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());

        final int messages=200000;
        final AtomicLong totalB=new AtomicLong();

        Thread consumer = new Thread()
        {
            @Override
            public void run()
            {
                // Thread.sleep is for artificially poor performance reader needed for this testcase.
                try
                {
                    Thread.sleep(200);
                    byte[] recv = new byte[32*1024];

                    int len=0;
                    while (len>=0)
                    {
                        totalB.addAndGet(len);
                        len=socket.getInputStream().read(recv,0,recv.length);
                        Thread.sleep(10);
                    }
                }
                catch(InterruptedException e)
                {
                    return;
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        consumer.start();

        // Send lots of messages client to server
        long start=System.currentTimeMillis();
        String mesg="This is a test message to send";
        for (int i=0;i<messages;i++)
        {
            connection.sendMessage(mesg);
        }

        // Duration for the write phase
        long writeDur = (System.currentTimeMillis() - start);

        // wait for consumer to complete
        while (totalB.get()<messages*(mesg.length()+6L))
        {
            Thread.sleep(10);
        }

        Assert.assertThat("write duration", writeDur, greaterThan(1000L)); // writing was blocked
        Assert.assertEquals(messages*(mesg.length()+6L),totalB.get());

        consumer.interrupt();
    }

    @Test
    public void testBlockReceiving() throws Exception
    {
        WebSocketClient client = new WebSocketClient(_factory);
        client.setMaxIdleTime(60000);

        final AtomicBoolean open = new AtomicBoolean();
        final AtomicInteger close = new AtomicInteger();
        final CountDownLatch _latch = new CountDownLatch(1);
        final StringBuilder closeMessage = new StringBuilder();
        final Exchanger<String> exchanger = new Exchanger<String>();
        Future<WebSocket.Connection> future=client.open(new URI("ws://127.0.0.1:"+_serverPort+"/"),new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
                open.set(true);
            }

            public void onClose(int closeCode, String message)
            {
                close.set(closeCode);
                closeMessage.append(message);
                _latch.countDown();
            }

            public void onMessage(String data)
            {
                try
                {
                    exchanger.exchange(data);
                }
                catch (InterruptedException e)
                {
                    // e.printStackTrace();
                }
            }
        });

        Socket socket = _server.accept();
        socket.setSoTimeout(60000);
        accept(socket);

        WebSocket.Connection connection = future.get(250,TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connection);
        Assert.assertTrue(open.get());
        Assert.assertEquals(0,close.get());

        // define some messages to send server to client
        byte[] send = new byte[] { (byte)0x81, (byte) 0x05,
                (byte)'H', (byte)'e', (byte)'l', (byte)'l',(byte)'o'  };
        final int messages=100000;
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
                        if (m.get() % 1000 == 0)
                        {
                            // Artificially slow reader
                            Thread.sleep(10);
                        }
                    }
                }
                catch(InterruptedException e)
                {
                    return;
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        consumer.start();

        long start=System.currentTimeMillis();
        for (int i=0;i<messages;i++)
        {
            socket.getOutputStream().write(send,0,send.length);
            socket.getOutputStream().flush();
        }

        while(consumer.isAlive())
        {
            Thread.sleep(10);
        }

        // Duration of the read operation.
        long readDur = (System.currentTimeMillis() - start);

        Assert.assertThat("read duration", readDur, greaterThan(1000L)); // reading was blocked
        Assert.assertEquals(m.get(),messages);

        // Close with code
        start=System.currentTimeMillis();
        socket.getOutputStream().write(new byte[]{(byte)0x88, (byte) 0x02, (byte)4, (byte)87 },0,4);
        socket.getOutputStream().flush();

        _latch.await(10,TimeUnit.SECONDS);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        Assert.assertEquals(1002,close.get());
        Assert.assertEquals("Invalid close code 1111", closeMessage.toString());
    }

    @Test
    public void testURIWithDefaultPort() throws Exception
    {
        URI uri = new URI("ws://localhost");
        InetSocketAddress addr = WebSocketClient.toSocketAddress(uri);
        Assert.assertThat("URI (" + uri + ").host", addr.getHostName(), is("localhost"));
        Assert.assertThat("URI (" + uri + ").port", addr.getPort(), is(80));
    }

    @Test
    public void testURIWithDefaultWSSPort() throws Exception
    {
        URI uri = new URI("wss://localhost");
        InetSocketAddress addr = WebSocketClient.toSocketAddress(uri);
        Assert.assertThat("URI (" + uri + ").host", addr.getHostName(), is("localhost"));
        Assert.assertThat("URI (" + uri + ").port", addr.getPort(), is(443));
    }

    private void respondToClient(Socket connection, String serverResponse) throws IOException
    {
        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader buf = null;
        OutputStream out = null;
        try {
            in = connection.getInputStream();
            isr = new InputStreamReader(in);
            buf = new BufferedReader(isr);
            String line;
            while((line = buf.readLine())!=null)
            {
                // System.err.println(line);
                if(line.length() == 0)
                {
                    // Got the "\r\n" line.
                    break;
                }
            }

            // System.out.println("[Server-Out] " + serverResponse);
            out = connection.getOutputStream();
            out.write(serverResponse.getBytes());
            out.flush();
        }
        finally
        {
            IO.close(buf);
            IO.close(isr);
            IO.close(in);
            IO.close(out);
        }
    }

    private void accept(Socket connection) throws IOException
    {
        String key="not sent";
        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        for (String line=in.readLine();line!=null;line=in.readLine())
        {
            if (line.length()==0)
                break;
            if (line.startsWith("Sec-WebSocket-Key:"))
                key=line.substring(18).trim();
        }
        connection.getOutputStream().write((
                "HTTP/1.1 101 Upgrade\r\n" +
                "Sec-WebSocket-Accept: "+ WebSocketConnectionRFC6455.hashKey(key) +"\r\n" +
                "\r\n").getBytes());
    }
}
