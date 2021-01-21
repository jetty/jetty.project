//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class StopTest
{
    private static final Logger LOG = LoggerFactory.getLogger(StopTest.class);

    /**
     * Test completed writes during shutdown do not close output
     *
     * @throws Exception on test failure
     */
    @Test
    public void testWriteDuringShutdown() throws Exception
    {
        Server server = new Server();
        server.setStopTimeout(1000);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ABHandler handler = new ABHandler();
        StatisticsHandler stats = new StatisticsHandler();
        server.setHandler(stats);
        stats.setHandler(handler);

        server.start();

        Thread stopper = new Thread(() ->
        {
            try
            {
                handler.latchA.await();
                server.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        stopper.start();

        final int port = connector.getLocalPort();
        try (Socket client = new Socket("127.0.0.1", port))
        {
            client.getOutputStream().write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + port + "\r\n" +
                    "\r\n"
            ).getBytes());
            client.getOutputStream().flush();

            while (!connector.isShutdown())
            {
                Thread.sleep(10);
            }

            handler.latchB.countDown();

            String response = IO.toString(client.getInputStream());
            assertThat(response, startsWith("HTTP/1.1 200 "));
            assertThat(response, containsString("Content-Length: 2"));
            assertThat(response, containsString("Connection: close"));
            assertThat(response, endsWith("ab"));
        }
        stopper.join();
    }

    public void testSlowClose(long stopTimeout, long closeWait, Matcher<Long> stopTimeMatcher) throws Exception
    {
        Server server = new Server();
        server.setStopTimeout(stopTimeout);

        FutureCallback closed = new FutureCallback();
        ServerConnector connector = new ServerConnector(server, 2, 2, new HttpConnectionFactory()
        {

            @Override
            public Connection newConnection(Connector con, EndPoint endPoint)
            {
                // Slow closing connection
                HttpConnection conn = new HttpConnection(getHttpConfiguration(), con, endPoint, isRecordHttpComplianceViolations())
                {
                    @Override
                    public void onClose(Throwable cause)
                    {
                        try
                        {
                            super.onClose(cause);
                        }
                        finally
                        {
                            if (cause == null)
                                closed.succeeded();
                            else
                                closed.failed(cause);
                        }
                    }

                    @Override
                    public void close()
                    {
                        long start = System.nanoTime();
                        new Thread(() ->
                        {
                            try
                            {
                                Thread.sleep(closeWait - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                            }
                            catch (Throwable e)
                            {
                                // no op
                            }
                            finally
                            {
                                super.close();
                            }
                        }).start();
                    }
                };
                return configure(conn, con, endPoint);
            }
        });
        connector.setPort(0);
        server.addConnector(connector);

        NoopHandler handler = new NoopHandler();
        server.setHandler(handler);

        server.start();
        final int port = connector.getLocalPort();
        Socket client = new Socket("127.0.0.1", port);
        client.setSoTimeout(10000);
        client.getOutputStream().write((
            "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: plain/text\r\n" +
                "\r\n"
        ).getBytes());
        client.getOutputStream().flush();
        handler.latch.await();

        // look for a response
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        while (true)
        {
            String line = in.readLine();
            assertThat("Line should not be null", line, is(notNullValue()));
            if (line.length() == 0)
                break;
        }

        long start = System.nanoTime();
        try
        {
            server.stop();
            assertTrue(stopTimeout == 0 || stopTimeout > closeWait);
        }
        catch (Exception e)
        {
            assertTrue(stopTimeout > 0 && stopTimeout < closeWait);
        }
        long stop = System.nanoTime();

        // Check stop time was correct
        assertThat(TimeUnit.NANOSECONDS.toMillis(stop - start), stopTimeMatcher);

        // Connection closed
        while (true)
        {
            int r = client.getInputStream().read();
            if (r == -1)
                break;
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start), lessThan(10L));
        }

        // onClose Thread interrupted or completed
        closed.get(Math.max(closeWait, stopTimeout) + 1000, TimeUnit.MILLISECONDS);

        if (!client.isClosed())
            client.close();
    }

    /**
     * Test of non graceful stop when a connection close is slow
     *
     * @throws Exception on test failure
     */
    @Test
    public void testSlowCloseNotGraceful() throws Exception
    {
        LOG.info("Expect some threads can't be stopped");
        testSlowClose(0, 5000, lessThan(750L));
    }

    /**
     * Test of graceful stop when close is slower than timeout
     *
     * @throws Exception on test failure
     */
    @Test
    public void testSlowCloseTinyGraceful() throws Exception
    {
        LOG.info("Expect some threads can't be stopped");
        testSlowClose(1, 5000, lessThan(1500L));
    }

    /**
     * Test of graceful stop when close is faster than timeout;
     *
     * @throws Exception on test failure
     */
    @Test
    public void testSlowCloseGraceful() throws Exception
    {
        testSlowClose(5000, 1000, Matchers.allOf(greaterThan(750L), lessThan(4999L)));
    }

    @Test
    public void testCommittedResponsesAreClosed() throws Exception
    {
        Server server = new Server();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        StatisticsHandler stats = new StatisticsHandler();
        server.setHandler(stats);

        ContextHandler context = new ContextHandler(stats, "/");

        Exchanger<Void> exchanger0 = new Exchanger<>();
        Exchanger<Void> exchanger1 = new Exchanger<>();
        context.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                try
                {
                    exchanger0.exchange(null);
                    exchanger1.exchange(null);
                }
                catch (Throwable x)
                {
                    throw new ServletException(x);
                }

                baseRequest.setHandled(true);
                response.setStatus(200);
                response.getWriter().println("The Response");
                response.getWriter().close();
            }
        });

        server.setStopTimeout(1000);
        server.start();

        LocalEndPoint endp = connector.executeRequest(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );

        exchanger0.exchange(null);
        exchanger1.exchange(null);

        String response = endp.getResponse();
        assertThat(response, containsString("200 OK"));
        assertThat(response, Matchers.not(containsString("Connection: close")));

        endp.addInputAndExecute(BufferUtil.toBuffer("GET / HTTP/1.1\r\nHost:localhost\r\n\r\n"));

        exchanger0.exchange(null);

        FutureCallback stopped = new FutureCallback();
        new Thread(() ->
        {
            try
            {
                server.stop();
                stopped.succeeded();
            }
            catch (Throwable e)
            {
                stopped.failed(e);
            }
        }).start();

        long start = System.nanoTime();
        while (!connector.isShutdown())
        {
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start), lessThan(10L));
            Thread.sleep(10);
        }

        // Check new connections rejected!
        assertThrows(IllegalStateException.class, () -> connector.getResponse("GET / HTTP/1.1\r\nHost:localhost\r\n\r\n"));

        // Check completed 200 has close
        exchanger1.exchange(null);
        response = endp.getResponse();
        assertThat(response, containsString("200 OK"));
        assertThat(response, Matchers.containsString("Connection: close"));
        stopped.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void testContextStop() throws Exception
    {
        Server server = new Server();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler(server, "/");

        StatisticsHandler stats = new StatisticsHandler();
        context.setHandler(stats);

        Exchanger<Void> exchanger0 = new Exchanger<>();
        Exchanger<Void> exchanger1 = new Exchanger<>();
        stats.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                try
                {
                    exchanger0.exchange(null);
                    exchanger1.exchange(null);
                }
                catch (Throwable x)
                {
                    throw new ServletException(x);
                }

                baseRequest.setHandled(true);
                response.setStatus(200);
                response.getWriter().println("The Response");
                response.getWriter().close();
            }
        });

        server.start();

        LocalEndPoint endp = connector.executeRequest(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n"
        );

        exchanger0.exchange(null);
        exchanger1.exchange(null);

        String response = endp.getResponse();
        assertThat(response, containsString("200 OK"));
        assertThat(response, Matchers.not(containsString("Connection: close")));

        endp.addInputAndExecute(BufferUtil.toBuffer("GET / HTTP/1.1\r\nHost:localhost\r\n\r\n"));
        exchanger0.exchange(null);

        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() ->
        {
            try
            {
                context.stop();
                latch.countDown();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }).start();
        while (context.isStarted())
        {
            Thread.sleep(10);
        }

        // Check new connections accepted, but don't find context!
        String unavailable = connector.getResponse("GET / HTTP/1.1\r\nHost:localhost\r\n\r\n");
        assertThat(unavailable, containsString(" 404 Not Found"));

        // Check completed 200 does not have close
        exchanger1.exchange(null);
        response = endp.getResponse();
        assertThat(response, containsString("200 OK"));
        assertThat(response, Matchers.not(Matchers.containsString("Connection: close")));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testFailedStart()
    {
        Server server = new Server();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        AtomicBoolean context0Started = new AtomicBoolean(false);
        ContextHandler context0 = new ContextHandler("/zero")
        {
            @Override
            protected void doStart() throws Exception
            {
                context0Started.set(true);
            }
        };
        ContextHandler context1 = new ContextHandler("/one")
        {
            @Override
            protected void doStart() throws Exception
            {
                throw new Exception("Test start failure");
            }
        };
        AtomicBoolean context2Started = new AtomicBoolean(false);
        ContextHandler context2 = new ContextHandler("/two")
        {
            @Override
            protected void doStart() throws Exception
            {
                context2Started.set(true);
            }
        };
        contexts.setHandlers(new Handler[]{context0, context1, context2});

        try
        {
            server.start();
            fail();
        }
        catch (Exception e)
        {
            assertThat(e.getMessage(), is("Test start failure"));
        }

        assertTrue(server.getContainedBeans(LifeCycle.class).stream().noneMatch(LifeCycle::isRunning));
        assertTrue(server.getContainedBeans(LifeCycle.class).stream().anyMatch(LifeCycle::isFailed));
        assertTrue(context0Started.get());
        assertFalse(context2Started.get());
    }

    static class NoopHandler extends AbstractHandler
    {
        final CountDownLatch latch = new CountDownLatch(1);

        NoopHandler()
        {
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            latch.countDown();
        }
    }

    static class ABHandler extends AbstractHandler
    {
        final CountDownLatch latchA = new CountDownLatch(1);
        final CountDownLatch latchB = new CountDownLatch(1);

        @Override
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentLength(2);
            response.getOutputStream().write("a".getBytes());
            try
            {
                latchA.countDown();
                latchB.await();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            response.flushBuffer();
            response.getOutputStream().write("b".getBytes());
        }
    }
}
