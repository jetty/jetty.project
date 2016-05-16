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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume;
import org.eclipse.jetty.util.thread.strategy.ProduceExecuteConsume;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ThreadStarvationTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    private QueuedThreadPool _threadPool;
    private Server _server;
    private ServerConnector _connector;
    private int _availableThreads;

    private Server prepareServer(Handler handler)
    {
        int threads = 4;
        _threadPool = new QueuedThreadPool();
        _threadPool.setMinThreads(threads);
        _threadPool.setMaxThreads(threads);
        _threadPool.setDetailedDump(true);
        _server = new Server(_threadPool);
        int acceptors = 1;
        int selectors = 1;
        _connector = new ServerConnector(_server, acceptors, selectors);
        _server.addConnector(_connector);
        _server.setHandler(handler);
        _availableThreads = threads - acceptors - selectors;
        return _server;
    }

    @After
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testReadInput() throws Exception
    {
        prepareServer(new ReadHandler()).start();

        Socket client = new Socket("localhost", _connector.getLocalPort());

        OutputStream os = client.getOutputStream();
        InputStream is = client.getInputStream();

        String request = "" +
                "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "0123456789\r\n";
        os.write(request.getBytes(StandardCharsets.UTF_8));
        os.flush();

        String response = IO.toString(is);
        assertEquals(-1, is.read());
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Read Input 10"));
    }

    @Test
    public void testEPCStarvation() throws Exception
    {
        testStarvation(new ExecuteProduceConsume.Factory());
    }

    @Test
    public void testPECStarvation() throws Exception
    {
        testStarvation(new ProduceExecuteConsume.Factory());
    }

    private void testStarvation(ExecutionStrategy.Factory executionFactory) throws Exception
    {
        prepareServer(new ReadHandler());
        _connector.setExecutionStrategyFactory(executionFactory);
        _server.start();
        System.err.println(_threadPool.dump());

        Socket[] client = new Socket[_availableThreads + 1];
        OutputStream[] os = new OutputStream[client.length];
        InputStream[] is = new InputStream[client.length];

        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("localhost", _connector.getLocalPort());
            client[i].setSoTimeout(10000);

            os[i] = client[i].getOutputStream();
            is[i] = client[i].getInputStream();

            String request = "" +
                    "PUT / HTTP/1.0\r\n" +
                    "host: localhost\r\n" +
                    "content-length: 10\r\n" +
                    "\r\n" +
                    "1";
            os[i].write(request.getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i = 0; i < client.length; i++)
        {
            os[i].write(("234567890\r\n").getBytes(StandardCharsets.UTF_8));
            os[i].flush();
        }

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (int i = 0; i < client.length; i++)
        {
            String response = IO.toString(is[i]);
            assertEquals(-1, is[i].read());
            assertThat(response, containsString("200 OK"));
            assertThat(response, containsString("Read Input 10"));
        }
    }

    @Test
    public void testEPCExitsLowThreadsMode() throws Exception
    {
        prepareServer(new ReadHandler());
        _threadPool.setMaxThreads(5);
        _connector.setExecutionStrategyFactory(new ExecuteProduceConsume.Factory());
        _server.start();
        System.err.println(_server.dump());

        // Three idle threads in the pool here.
        // The server will accept the socket in normal mode.
        Socket client = new Socket("localhost", _connector.getLocalPort());
        client.setSoTimeout(10000);

        Thread.sleep(500);

        // Now steal two threads.
        CountDownLatch[] latches = new CountDownLatch[2];
        for (int i = 0; i < latches.length; ++i)
        {
            CountDownLatch latch = latches[i] = new CountDownLatch(1);
            _threadPool.execute(() ->
            {
                try
                {
                    latch.await();
                }
                catch (InterruptedException ignored)
                {
                }
            });
        }

        InputStream is = client.getInputStream();
        OutputStream os = client.getOutputStream();

        String request = "" +
                "PUT / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "1";
        os.write(request.getBytes(StandardCharsets.UTF_8));
        os.flush();

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        // Request did not send the whole body, Handler
        // is blocked reading, zero idle threads here,
        // EPC is in low threads mode.
        for (ManagedSelector selector : _connector.getSelectorManager().getBeans(ManagedSelector.class))
        {
            ExecuteProduceConsume executionStrategy = (ExecuteProduceConsume)selector.getExecutionStrategy();
            assertTrue(executionStrategy.isLowOnThreads());
        }

        // Release the stolen threads.
        for (CountDownLatch latch : latches)
            latch.countDown();
        Thread.sleep(500);

        // Send the rest of the body to unblock the reader thread.
        // This will be run directly by the selector thread,
        // which then will exit the low threads mode.
        os.write("234567890".getBytes(StandardCharsets.UTF_8));
        os.flush();

        Thread.sleep(500);
        System.err.println(_threadPool.dump());

        for (ManagedSelector selector : _connector.getSelectorManager().getBeans(ManagedSelector.class))
        {
            ExecuteProduceConsume executionStrategy = (ExecuteProduceConsume)selector.getExecutionStrategy();
            assertFalse(executionStrategy.isLowOnThreads());
        }
    }

    protected static class ReadHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);

            int l = request.getContentLength();
            int r = 0;
            while (r < l)
            {
                if (request.getInputStream().read() >= 0)
                    r++;
            }

            response.getOutputStream().write(("Read Input " + r + "\r\n").getBytes());
        }
    }
}
