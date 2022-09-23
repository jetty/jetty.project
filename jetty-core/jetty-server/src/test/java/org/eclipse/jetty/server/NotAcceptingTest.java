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
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.HelloHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

@Disabled // TODO
public class NotAcceptingTest
{
    private static final Logger LOG = LoggerFactory.getLogger(NotAcceptingTest.class);
    private final long idleTimeout = 2000;
    Server server;
    LocalConnector localConnector;
    ServerConnector blockingConnector;
    ServerConnector asyncConnector;

    @BeforeEach
    public void before()
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        localConnector.setIdleTimeout(idleTimeout);
        server.addConnector(localConnector);

        blockingConnector = new ServerConnector(server, 1, 1);
        blockingConnector.setPort(0);
        blockingConnector.setIdleTimeout(idleTimeout);
        blockingConnector.setAcceptQueueSize(10);
        server.addConnector(blockingConnector);

        asyncConnector = new ServerConnector(server, 0, 1);
        asyncConnector.setPort(0);
        asyncConnector.setIdleTimeout(idleTimeout);
        asyncConnector.setAcceptQueueSize(10);
        server.addConnector(asyncConnector);
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        server = null;
    }

    @Test
    public void testServerConnectorBlockingAccept() throws Exception
    {
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();

        try (Socket client0 = new Socket("localhost", blockingConnector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri, is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("data"));

            blockingConnector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri, is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("more data"));

            try (Socket client1 = new Socket("localhost", blockingConnector.getLocalPort());)
            {
                // can't stop next connection being accepted
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                uri = handler.exchange.exchange("new connection");
                assertThat(uri, is("/three"));
                response = HttpTester.parseResponse(in1);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is("new connection"));

                try (Socket client2 = new Socket("localhost", blockingConnector.getLocalPort());)
                {

                    HttpTester.Input in2 = HttpTester.from(client2.getInputStream());
                    client2.getOutputStream().write("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());

                    try
                    {
                        uri = handler.exchange.exchange("delayed connection", idleTimeout, TimeUnit.MILLISECONDS);
                        fail("Failed near URI: " + uri); // this displays last URI, not current (obviously)
                    }
                    catch (TimeoutException e)
                    {
                        // Can we accept the original?
                        blockingConnector.setAccepting(true);
                        uri = handler.exchange.exchange("delayed connection");
                        assertThat(uri, is("/four"));
                        response = HttpTester.parseResponse(in2);
                        assertThat(response.getStatus(), is(200));
                        assertThat(response.getContent(), is("delayed connection"));
                    }
                }
            }
        }
    }

    @Test
    @Disabled
    public void testLocalConnector() throws Exception
    {
        server.setHandler(new HelloHandler());
        server.start();

        try (LocalEndPoint client0 = localConnector.connect())
        {
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            HttpTester.Response response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("Hello\n"));

            localConnector.setAccepting(false);

            // 0th connection still working
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("Hello\n"));

            LocalEndPoint[] local = new LocalEndPoint[10];
            for (int i = 0; i < 10; i++)
            {
                try (LocalEndPoint client = localConnector.connect())
                {
                    try
                    {
                        local[i] = client;
                        client.addInputAndExecute(BufferUtil.toBuffer("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                        response = HttpTester.parseResponse(client.getResponse(false, idleTimeout, TimeUnit.MILLISECONDS));

                        // A few local connections may succeed
                        if (i == local.length - 1)
                            // but not 10 of them!
                            fail("Expected TimeoutException");
                    }
                    catch (TimeoutException e)
                    {
                        // A connection finally failed!
                        break;
                    }
                }
            }
            // 0th connection still working
            client0.addInputAndExecute(BufferUtil.toBuffer("GET /four HTTP/1.1\r\nHost:localhost\r\n\r\n"));
            response = HttpTester.parseResponse(client0.getResponse());
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("Hello\n"));

            localConnector.setAccepting(true);
            // New connection working again
            try (LocalEndPoint client = localConnector.connect())
            {
                client.addInputAndExecute(BufferUtil.toBuffer("GET /five HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                response = HttpTester.parseResponse(client.getResponse());
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is("Hello\n"));
            }
        }
    }

    @Test
    public void testServerConnectorAsyncAccept() throws Exception
    {
        TestHandler handler = new TestHandler();
        server.setHandler(handler);

        server.start();

        try (Socket client0 = new Socket("localhost", asyncConnector.getLocalPort());)
        {
            HttpTester.Input in0 = HttpTester.from(client0.getInputStream());

            client0.getOutputStream().write("GET /one HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            String uri = handler.exchange.exchange("data");
            assertThat(uri, is("/one"));
            HttpTester.Response response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("data"));

            asyncConnector.setAccepting(false);

            // 0th connection still working
            client0.getOutputStream().write("GET /two HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
            uri = handler.exchange.exchange("more data");
            assertThat(uri, is("/two"));
            response = HttpTester.parseResponse(in0);
            assertThat(response.getStatus(), is(200));
            assertThat(response.getContent(), is("more data"));

            try (Socket client1 = new Socket("localhost", asyncConnector.getLocalPort());)
            {
                HttpTester.Input in1 = HttpTester.from(client1.getInputStream());
                client1.getOutputStream().write("GET /three HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());

                try
                {
                    uri = handler.exchange.exchange("delayed connection", idleTimeout, TimeUnit.MILLISECONDS);
                    fail(uri);
                }
                catch (TimeoutException e)
                {
                    // Can we accept the original?
                    asyncConnector.setAccepting(true);
                    uri = handler.exchange.exchange("delayed connection");
                    assertThat(uri, is("/three"));
                    response = HttpTester.parseResponse(in1);
                    assertThat(response.getStatus(), is(200));
                    assertThat(response.getContent(), is("delayed connection"));
                }
            }
        }
    }

    public static class TestHandler extends Handler.Processor
    {
        final Exchanger<String> exchange = new Exchanger<>();
        transient int handled;

        public TestHandler()
        {
        }

        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            // TODO see below
        }
        /*
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                String content = exchange.exchange(baseRequest.getRequestURI());
                baseRequest.setHandled(true);
                handled++;
                response.setContentType("text/html;charset=utf-8");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().print(content);
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
        }
        */

        public int getHandled()
        {
            return handled;
        }
    }

    @Test
    public void testAcceptRateLimit() throws Exception
    {
        AcceptRateLimit limit = new AcceptRateLimit(4, 1, TimeUnit.HOURS, server);
        server.addBean(limit);
        server.setHandler(new HelloHandler());

        server.start();

        try (
            Socket async0 = new Socket("localhost", asyncConnector.getLocalPort());
            Socket async1 = new Socket("localhost", asyncConnector.getLocalPort());
            Socket async2 = new Socket("localhost", asyncConnector.getLocalPort());
        )
        {
            String expectedContent = "Hello" + System.lineSeparator();

            for (Socket client : new Socket[]{async2})
            {
                HttpTester.Input in = HttpTester.from(client.getInputStream());
                client.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is(expectedContent));
            }

            assertThat(localConnector.isAccepting(), is(true));
            assertThat(blockingConnector.isAccepting(), is(true));
            assertThat(asyncConnector.isAccepting(), is(true));
        }

        limit.age(45, TimeUnit.MINUTES);

        try (
            Socket async0 = new Socket("localhost", asyncConnector.getLocalPort());
            Socket async1 = new Socket("localhost", asyncConnector.getLocalPort());
        )
        {
            String expectedContent = "Hello" + System.lineSeparator();

            for (Socket client : new Socket[]{async1})
            {
                HttpTester.Input in = HttpTester.from(client.getInputStream());
                client.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is(expectedContent));
            }

            assertThat(localConnector.isAccepting(), is(false));
            assertThat(blockingConnector.isAccepting(), is(false));
            assertThat(asyncConnector.isAccepting(), is(false));
        }

        limit.age(45, TimeUnit.MINUTES);
        assertThat(localConnector.isAccepting(), is(false));
        assertThat(blockingConnector.isAccepting(), is(false));
        assertThat(asyncConnector.isAccepting(), is(false));
        limit.run();
        assertThat(localConnector.isAccepting(), is(true));
        assertThat(blockingConnector.isAccepting(), is(true));
        assertThat(asyncConnector.isAccepting(), is(true));
    }

    @Test
    public void testConnectionLimit() throws Exception
    {
        server.addBean(new ConnectionLimit(9, server));
        server.setHandler(new HelloHandler());

        server.start();

        LOG.debug("CONNECT:");
        try (
            LocalEndPoint local0 = localConnector.connect();
            LocalEndPoint local1 = localConnector.connect();
            LocalEndPoint local2 = localConnector.connect();
            Socket blocking0 = new Socket("localhost", blockingConnector.getLocalPort());
            Socket blocking1 = new Socket("localhost", blockingConnector.getLocalPort());
            Socket blocking2 = new Socket("localhost", blockingConnector.getLocalPort());
            Socket async0 = new Socket("localhost", asyncConnector.getLocalPort());
            Socket async1 = new Socket("localhost", asyncConnector.getLocalPort());
            Socket async2 = new Socket("localhost", asyncConnector.getLocalPort());
        )
        {
            String expectedContent = "Hello" + System.lineSeparator();

            LOG.debug("LOCAL:");
            for (LocalEndPoint client : new LocalEndPoint[]{local0, local1, local2})
            {
                client.addInputAndExecute(BufferUtil.toBuffer("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n"));
                HttpTester.Response response = HttpTester.parseResponse(client.getResponse());
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is(expectedContent));
            }

            LOG.debug("NETWORK:");
            for (Socket client : new Socket[]{blocking0, blocking1, blocking2, async0, async1, async2})
            {
                HttpTester.Input in = HttpTester.from(client.getInputStream());
                client.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is(expectedContent));
            }

            assertThat(localConnector.isAccepting(), is(false));
            assertThat(blockingConnector.isAccepting(), is(false));
            assertThat(asyncConnector.isAccepting(), is(false));

            {
                // Close a async connection
                HttpTester.Input in = HttpTester.from(async1.getInputStream());
                async1.getOutputStream().write("GET /test HTTP/1.1\r\nHost:localhost\r\nConnection: close\r\n\r\n".getBytes());
                HttpTester.Response response = HttpTester.parseResponse(in);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), is(expectedContent));
            }
        }

        waitFor(localConnector::isAccepting, is(true), 2 * idleTimeout, TimeUnit.MILLISECONDS);
        waitFor(blockingConnector::isAccepting, is(true), 2 * idleTimeout, TimeUnit.MILLISECONDS);
        waitFor(asyncConnector::isAccepting, is(true), 2 * idleTimeout, TimeUnit.MILLISECONDS);
    }

    public static <T> void waitFor(Supplier<T> value, Matcher<T> matcher, long wait, TimeUnit units)
    {
        long start = NanoTime.now();

        while (true)
        {
            try
            {
                matcher.matches(value.get());
                return;
            }
            catch (Throwable e)
            {
                if (NanoTime.since(start) > units.toNanos(wait))
                    throw e;
            }

            try
            {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            catch (InterruptedException e)
            {
                // no op
            }
        }
    }
}
