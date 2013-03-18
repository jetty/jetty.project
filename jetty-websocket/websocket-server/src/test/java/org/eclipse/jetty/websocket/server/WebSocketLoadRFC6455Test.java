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

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.server.examples.MyEchoSocket;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class WebSocketLoadRFC6455Test
{
    private class WebSocketClient implements Runnable
    {
        private final Socket socket;
        private final BufferedWriter output;
        private final BufferedReader input;
        private final int iterations;
        private final CountDownLatch latch;
        private/* final */EndPoint _endp;
        private final Generator _generator;
        private final Parser _parser;
        private final IncomingFrames _handler = new IncomingFrames()
        {
            @Override
            public void incomingError(WebSocketException e)
            {
            }

            @Override
            public void incomingFrame(Frame frame)
            {
            }
        };
        private volatile ByteBuffer _response;

        public WebSocketClient(String host, int port, int readTimeout, CountDownLatch latch, int iterations) throws IOException
        {
            this.latch = latch;
            socket = new Socket(host,port);
            socket.setSoTimeout(readTimeout);
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"ISO-8859-1"));
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(),"ISO-8859-1"));
            this.iterations = iterations;

            WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

            // _endp=new SocketEndPoint(socket);
            ByteBufferPool bufferPool = new MappedByteBufferPool();
            _generator = new Generator(policy,bufferPool);
            _parser = new Parser(policy,bufferPool);

        }

        public void close() throws IOException
        {
            socket.close();
        }

        private void open() throws IOException
        {
            output.write("GET /chat HTTP/1.1\r\n" + "Host: server.example.com\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" + "Sec-WebSocket-Origin: http://example.com\r\n"
                    + "Sec-WebSocket-Protocol: onConnect\r\n" + "Sec-WebSocket-Version: 7\r\n" + "\r\n");
            output.flush();

            String responseLine = input.readLine();
            assertTrue(responseLine.startsWith("HTTP/1.1 101 Switching Protocols"));
            // Read until we find an empty line, which signals the end of the http response
            String line;
            while ((line = input.readLine()) != null)
            {
                if (line.length() == 0)
                {
                    break;
                }
            }
        }

        @Override
        public void run()
        {
            try
            {
                String message = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
                for (int i = 0; i < iterations; ++i)
                {
                    WebSocketFrame txt = WebSocketFrame.text(message);
                    ByteBuffer buf = _generator.generate(txt);

                    // TODO: Send it
                    // TODO: Receive response

                    Assert.assertEquals(message,_response.toString());
                    latch.countDown();
                }
            }
            catch (Throwable x)
            {
                throw new RuntimeException(x);
            }
        }
    }

    private static Server _server;

    private static ServerConnector _connector;

    @BeforeClass
    public static void startServer() throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool(200);
        threadPool.setStopTimeout(1000);
        _server = new Server(threadPool);
        _server.manage(threadPool);

        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketHandler wsHandler = new WebSocketHandler.Simple(MyEchoSocket.class);
        wsHandler.setHandler(new DefaultHandler());
        _server.setHandler(wsHandler);

        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    @Ignore("Not yet converted to new jetty-9 structure")
    public void testLoad() throws Exception
    {
        int count = 50;
        int iterations = 100;

        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            CountDownLatch latch = new CountDownLatch(count * iterations);
            WebSocketClient[] clients = new WebSocketClient[count];
            for (int i = 0; i < clients.length; ++i)
            {
                clients[i] = new WebSocketClient("localhost",_connector.getLocalPort(),1000,latch,iterations);
                clients[i].open();
            }

            // long start = System.nanoTime();
            for (WebSocketClient client : clients)
            {
                threadPool.execute(client);
            }

            int parallelism = ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
            long maxTimePerIteration = 5;
            assertTrue(latch.await(iterations * ((count / parallelism) + 1) * maxTimePerIteration,TimeUnit.MILLISECONDS));
            // long end = System.nanoTime();
            // System.err.println("Elapsed: " + TimeUnit.NANOSECONDS.toMillis(end - start) + " ms");

            for (WebSocketClient client : clients)
            {
                client.close();
            }
        }
        finally
        {
            threadPool.shutdown();
            assertTrue(threadPool.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
}
