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

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SSLSelectChannelConnectorLoadTest
{
    private static Server server;
    private static ServerConnector connector;
    private static SSLContext sslContext;

    @BeforeAll
    public static void startServer() throws Exception
    {
        String keystorePath = System.getProperty("basedir", ".") + "/src/test/resources/keystore.p12";
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(new EmptyHandler());

        server.start();

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = new FileInputStream(keystorePath))
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testGetURI()
    {
        URI uri = server.getURI();
        assertThat("Server.uri.scheme", uri.getScheme(), is("https"));
        assertThat("Server.uri.port", uri.getPort(), is(connector.getLocalPort()));
        assertThat("Server.uri.path", uri.getPath(), is("/"));
    }

    @Test
    public void testLongLivedConnections() throws Exception
    {
        Worker.totalIterations.set(0);

        int mebiByte = 1048510;
        int clients = 1;
        int iterations = 2;
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(clients, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        threadPool.prestartAllCoreThreads();
        Worker[] workers = new Worker[clients];
        Future[] tasks = new Future[clients];
        for (int i = 0; i < clients; ++i)
        {
            workers[i] = new Worker(sslContext, iterations, false, mebiByte, 64 * mebiByte);
            workers[i].open();
            tasks[i] = threadPool.submit(workers[i]);
        }

        while (true)
        {
            Thread.sleep(1000);
            boolean done = true;
            for (Future task : tasks)
            {
                done &= task.isDone();
            }
            if (done)
                break;
        }

        for (Worker worker : workers)
        {
            worker.close();
        }

        threadPool.shutdown();

        // Throw exceptions if any
        for (Future task : tasks)
        {
            task.get();
        }

        // Keep the JVM running
//        new CountDownLatch(1).await();
    }

    @Test
    public void testShortLivedConnections() throws Exception
    {
        Worker.totalIterations.set(0);

        int mebiByte = 1048510;
        int clients = 1;
        int iterations = 2;
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(clients, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        threadPool.prestartAllCoreThreads();
        Worker[] workers = new Worker[clients];
        Future[] tasks = new Future[clients];
        for (int i = 0; i < clients; ++i)
        {
            workers[i] = new Worker(sslContext, iterations, true, mebiByte, 64 * mebiByte);
            tasks[i] = threadPool.submit(workers[i]);
        }

        while (true)
        {
            Thread.sleep(1000);
            boolean done = true;
            for (Future task : tasks)
            {
                done &= task.isDone();
            }
            if (done)
                break;
        }

        threadPool.shutdown();

        // Throw exceptions if any
        for (Future task : tasks)
        {
            task.get();
        }

        // Keep the JVM running
//        new CountDownLatch(1).await();
    }

    private static class Worker implements Runnable
    {
        private static final AtomicLong totalIterations = new AtomicLong();
        private final SSLContext sslContext;
        private volatile SSLSocket sslSocket;
        private final int iterations;
        private final boolean closeConnection;
        private final int minContent;
        private final int maxContent;

        public Worker(SSLContext sslContext, int iterations, boolean closeConnection, int minContent, int maxContent)
        {
            this.sslContext = sslContext;
            this.iterations = iterations;
            this.closeConnection = closeConnection;
            this.minContent = minContent;
            this.maxContent = maxContent;
        }

        public void open() throws IOException
        {
            this.sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort());
        }

        public void close() throws IOException
        {
            sslSocket.close();
        }

        public void run()
        {
            try
            {
                Random random = new Random();

                StringBuilder builder = new StringBuilder();
                OutputStream out = null;
                InputStream in = null;
                if (!closeConnection)
                {
                    open();
                    out = sslSocket.getOutputStream();
                    in = sslSocket.getInputStream();
                }

                for (int i = 0; i < iterations; ++i)
                {
                    if (closeConnection)
                    {
                        open();
                        out = sslSocket.getOutputStream();
                        in = sslSocket.getInputStream();
                    }

                    int contentSize = random.nextInt(maxContent - minContent) + minContent;
//                    System.err.println("Writing " + content + " request bytes");
                    out.write("POST / HTTP/1.1\r\n".getBytes());
                    out.write("Host: localhost\r\n".getBytes());
                    out.write(("Content-Length: " + contentSize + "\r\n").getBytes());
                    out.write("Content-Type: application/octect-stream\r\n".getBytes());
                    if (closeConnection)
                        out.write("Connection: close\r\n".getBytes());
                    out.write("\r\n".getBytes());
                    out.flush();
                    byte[] contentChunk = new byte[minContent];
                    int content = contentSize;
                    while (content > 0)
                    {
                        int chunk = Math.min(content, contentChunk.length);
                        Arrays.fill(contentChunk, 0, chunk, (byte)'x');
                        out.write(contentChunk, 0, chunk);
                        content -= chunk;
                    }
                    out.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    int responseLength = 0;
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
//                        System.err.println(line);
                        String contentLength = "Content-Length:";
                        if (line.startsWith(contentLength))
                        {
                            responseLength = Integer.parseInt(line.substring(contentLength.length()).trim());
                        }
                        else if (line.length() == 0)
                        {
                            if (responseLength == 0)
                                line = reader.readLine();
                            break;
                        }
                    }

                    builder.setLength(0);
                    if (responseLength > 0)
                    {
                        for (int j = 0; j < responseLength; ++j)
                        {
                            builder.append((char)reader.read());
                        }
                    }
                    else
                    {
                        builder.append(line);
                    }

                    if (closeConnection)
                        close();

                    if (contentSize != Integer.parseInt(builder.toString()))
                        throw new IllegalStateException();

                    Thread.sleep(random.nextInt(1000));

                    totalIterations.incrementAndGet();
                }

                if (!closeConnection)
                    close();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
    }

    private static class EmptyHandler extends AbstractHandler
    {
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            InputStream in = request.getInputStream();
            int total = 0;
            byte[] b = new byte[1024 * 1024];
            int read;
            while ((read = in.read(b)) >= 0)
            {
                total += read;
            }
//            System.err.println("Read " + total + " request bytes");
            httpResponse.getOutputStream().write(String.valueOf(total).getBytes());
        }
    }
}
