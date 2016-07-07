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

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ThreadStarvationTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    private Server _server;

    @After
    public void dispose() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    @Slow
    public void testDefaultServletSuccess() throws Exception
    {
        int maxThreads = 10;
        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, maxThreads);
        threadPool.setDetailedDump(true);
        _server = new Server(threadPool);

        // Prepare a big file to download.
        File directory = MavenTestingUtils.getTargetTestingDir();
        Files.createDirectories(directory.toPath());
        String resourceName = "resource.bin";
        Path resourcePath = Paths.get(directory.getPath(), resourceName);
        try (OutputStream output = Files.newOutputStream(resourcePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        {
            byte[] chunk = new byte[1024];
            Arrays.fill(chunk,(byte)'X');
            chunk[chunk.length-2]='\r';
            chunk[chunk.length-1]='\n';
            for (int i = 0; i < 256 * 1024; ++i)
                output.write(chunk);
        }

        final CountDownLatch writePending = new CountDownLatch(1);
        ServerConnector connector = new ServerConnector(_server, 0, 1)
        {
            @Override
            protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                return new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    protected void onIncompleteFlush()
                    {
                        super.onIncompleteFlush();
                        writePending.countDown();
                    }
                };
            }
        };
        connector.setIdleTimeout(Long.MAX_VALUE);
        _server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(_server, "/");
        context.setResourceBase(directory.toURI().toString());
        context.addServlet(DefaultServlet.class, "/*").setAsyncSupported(false);
        _server.setHandler(context);

        _server.start();

        List<Socket> sockets = new ArrayList<>();
        for (int i = 0; i < maxThreads*2; ++i)
        {
            Socket socket = new Socket("localhost", connector.getLocalPort());
            sockets.add(socket);
            OutputStream output = socket.getOutputStream();
            String request = "" +
                    "GET /" + resourceName + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(100);
        }

        // Wait for a the servlet to block.
        Assert.assertTrue(writePending.await(5, TimeUnit.SECONDS));

        long expected = Files.size(resourcePath);
        byte[] buffer = new byte[48 * 1024];
        List<Exchanger<Long>> totals = new ArrayList<>();
        for (Socket socket : sockets)
        {
            final Exchanger<Long> x = new Exchanger<>();
            totals.add(x);
            final InputStream input = socket.getInputStream();

            new Thread()
            {
                @Override
                public void run()
                {
                    long total=0;
                    try
                    {
                        // look for CRLFCRLF
                        StringBuilder header = new StringBuilder();
                        int state=0;
                        while (state<4 && header.length()<2048)
                        {
                            int ch=input.read();
                            if (ch<0)
                                break;
                            header.append((char)ch);
                            switch(state)
                            {
                                case 0:
                                    if (ch=='\r')
                                        state=1;
                                    break;
                                case 1:
                                    if (ch=='\n')
                                        state=2;
                                    else
                                        state=0;
                                    break;
                                case 2:
                                    if (ch=='\r')
                                        state=3;
                                    else
                                        state=0;
                                    break;
                                case 3:
                                    if (ch=='\n')
                                        state=4;
                                    else
                                        state=0;
                                    break;
                            }
                        }

                        while (total<expected)
                        {
                            int read=input.read(buffer);
                            if (read<0)
                                break;
                            total+=read;
                        }
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        try
                        {
                            x.exchange(total);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }
                }
            }.start();
        }

        for (Exchanger<Long> x : totals)
        {
            Long total = x.exchange(-1L,10000,TimeUnit.SECONDS);
            Assert.assertEquals(expected,total.longValue());
        }
        
        // We could read everything, good.
        for (Socket socket : sockets)
            socket.close();
    }
    
    @Test
    public void testFailureStarvation() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            int acceptors = 0;
            int selectors = 1;
            int maxThreads = 10;
            final int barried=maxThreads-acceptors-selectors*2;
            final CyclicBarrier barrier = new CyclicBarrier(barried);


            QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, maxThreads);
            threadPool.setDetailedDump(true);
            _server = new Server(threadPool);


            ServerConnector connector = new ServerConnector(_server, acceptors, selectors)
            {
                @Override
                protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
                {
                    return new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                    {
                        @Override
                        public boolean flush(ByteBuffer... buffers) throws IOException
                        {
                            super.flush(buffers[0]);
                            throw new IOException("TEST FAILURE");
                        }
                    };
                }
            };
            connector.setIdleTimeout(Long.MAX_VALUE);
            _server.addConnector(connector);

            final AtomicInteger count = new AtomicInteger(0);
            _server.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    int c=count.getAndIncrement();
                    try
                    {
                        if (c<barried)
                        {
                            barrier.await(10,TimeUnit.SECONDS);
                        }
                    }
                    catch (InterruptedException | BrokenBarrierException | TimeoutException e)
                    {
                        throw new ServletException(e);
                    }
                    baseRequest.setHandled(true);
                    response.setStatus(200);
                    response.setContentLength(13);
                    response.getWriter().print("Hello World!\n");
                    response.getWriter().flush();
                }
            });

            _server.start();

            List<Socket> sockets = new ArrayList<>();
            for (int i = 0; i < maxThreads*2; ++i)
            {
                Socket socket = new Socket("localhost", connector.getLocalPort());
                sockets.add(socket);
                OutputStream output = socket.getOutputStream();
                String request = "" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        //                    "Connection: close\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            byte[] buffer = new byte[48 * 1024];
            List<Exchanger<Integer>> totals = new ArrayList<>();
            for (Socket socket : sockets)
            {
                final Exchanger<Integer> x = new Exchanger<>();
                totals.add(x);
                final InputStream input = socket.getInputStream();

                new Thread()
                {
                    @Override
                    public void run()
                    {
                        int read=0;
                        try
                        {
                            // look for CRLFCRLF
                            StringBuilder header = new StringBuilder();
                            int state=0;
                            while (state<4 && header.length()<2048)
                            {
                                int ch=input.read();
                                if (ch<0)
                                    break;
                                header.append((char)ch);
                                switch(state)
                                {
                                    case 0:
                                        if (ch=='\r')
                                            state=1;
                                        break;
                                    case 1:
                                        if (ch=='\n')
                                            state=2;
                                        else
                                            state=0;
                                        break;
                                    case 2:
                                        if (ch=='\r')
                                            state=3;
                                        else
                                            state=0;
                                        break;
                                    case 3:
                                        if (ch=='\n')
                                            state=4;
                                        else
                                            state=0;
                                        break;
                                }
                            }

                            read=input.read(buffer);
                        }
                        catch (IOException e)
                        {
                            // e.printStackTrace();
                        }
                        finally
                        {
                            try
                            {
                                x.exchange(read);
                            }
                            catch (InterruptedException e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }
                }.start();
            }

            for (Exchanger<Integer> x : totals)
            {
                Integer read = x.exchange(-1,10,TimeUnit.SECONDS);
                Assert.assertEquals(-1,read.intValue());
            }

            // We could read everything, good.
            for (Socket socket : sockets)
                socket.close();
            
            _server.stop();
        }
    }
}
