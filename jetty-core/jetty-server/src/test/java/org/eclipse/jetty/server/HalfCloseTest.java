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

package org.eclipse.jetty.server;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled // TODO
public class HalfCloseTest
{
    @Test
    public void testHalfCloseRace() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setPort(0);
        connector.setIdleTimeout(500);
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();

        try (Socket client = new Socket("localhost", connector.getLocalPort());)
        {
            int in = client.getInputStream().read();
            assertEquals(-1, in);

            client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());

            Thread.sleep(200);
            assertEquals(0, handler.getHandled());
        }
    }

    @Test
    public void testCompleteClose() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setPort(0);
        connector.setIdleTimeout(5000);
        final AtomicInteger opened = new AtomicInteger(0);
        final CountDownLatch closed = new CountDownLatch(1);
        connector.addBean(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
                opened.incrementAndGet();
            }

            @Override
            public void onClosed(Connection connection)
            {
                closed.countDown();
            }
        });
        server.addConnector(connector);
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();

        try (Socket client = new Socket("localhost", connector.getLocalPort());)
        {
            client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());
            IO.toString(client.getInputStream());
            assertEquals(1, handler.getHandled());
            assertEquals(1, opened.get());
        }
        assertEquals(true, closed.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncClose() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setPort(0);
        connector.setIdleTimeout(5000);
        final AtomicInteger opened = new AtomicInteger(0);
        final CountDownLatch closed = new CountDownLatch(1);
        connector.addBean(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
                opened.incrementAndGet();
            }

            @Override
            public void onClosed(Connection connection)
            {
                closed.countDown();
            }
        });
        server.addConnector(connector);
        AsyncHandler handler = new AsyncHandler();
        server.setHandler(handler);

        server.start();

        try (Socket client = new Socket("localhost", connector.getLocalPort());)
        {
            client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());
            IO.toString(client.getInputStream());
            assertEquals(1, handler.getHandled());
            assertEquals(1, opened.get());
        }
        assertEquals(true, closed.await(1, TimeUnit.SECONDS));
    }

    public static class TestHandler extends Handler.Abstract
    {
        transient int handled;

        public TestHandler()
        {
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            handled++;
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");
            response.setStatus(HttpStatus.OK_200);
            Content.Sink.write(response, true, "<h1>Test</h1>\n", callback);
            return true;
        }

        public int getHandled()
        {
            return handled;
        }
    }

    public static class AsyncHandler extends Handler.Abstract
    {
        transient int handled;

        public AsyncHandler()
        {
        }

        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            handled++;

            new Thread(() ->
            {
                try
                {
                    response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");
                    response.setStatus(HttpStatus.OK_200);
                    Content.Sink.write(response, true, "<h1>Test</h1>\n", callback);
                }
                catch (Exception ex)
                {
                    System.err.println(ex);
                    callback.failed(ex);
                }
            }).start();
            return true;
        }

        public int getHandled()
        {
            return handled;
        }
    }
}
