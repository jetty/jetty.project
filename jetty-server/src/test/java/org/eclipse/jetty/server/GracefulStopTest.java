//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class GracefulStopTest
{
    public abstract static class Scenario
    {
        Server server;
        Connector connector;
        StatisticsHandler stats;
        ContextHandler context;
        TestHandler handler;
        long stopping;

        public Scenario()
        {
            server = new Server();
            server.setStopTimeout(10000);

            connector = newConnector(server);
            server.addConnector(connector);

            stats = new StatisticsHandler();
            server.setHandler(stats);

            context = new ContextHandler();
            context.setContextPath("/");
            context.setStopTimeout(10000);
            stats.setHandler(context);

            handler = new TestHandler();
            context.setHandler(handler);
        }

        abstract Connector newConnector(Server server);

        void start() throws Exception
        {
            server.start();
        }

        void shutdown() throws Exception
        {
            new Thread(()->
            {
                try
                {
                    server.stop();
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            while(!stats.isShutdown() && !stats.isStopped())
            {
                Thread.sleep(100);
            }
            stopping = System.nanoTime();
        }

        public abstract Object connect() throws Exception;

        public abstract void request(Object connection, String method, String uri, int contentLength, String content) throws Exception;

        public abstract void send(Object connection, String method) throws Exception;

        public abstract HttpTester.Response response(Object connection) throws Exception;

        public void checkLastResponse(HttpTester.Response response)
        {
            assertThat(response.get(HttpHeader.CONNECTION), is("close"));
        }

        abstract void waitForClosed(Object connection) throws Exception;

        void join() throws Exception
        {
            server.join();
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stopping), lessThan(9L));
        }
    }

    public static Stream<Scenario> scenarios() throws IOException
    {
        return Stream.of(
            new Scenario()
            {
                @Override
                Connector newConnector(Server server)
                {
                    ServerConnector connector = new ServerConnector(server);
                    connector.setPort(0);
                    return connector;
                }

                @Override
                public Object connect() throws Exception
                {
                    try
                    {
                        int port = ((ServerConnector)connector).getLocalPort();
                        Socket client = new Socket("127.0.0.1", port);
                        return client;
                    }
                    catch (Throwable th)
                    {
                        Log.getRootLogger().ignore(th);
                    }
                    return null;
                }

                @Override
                public void request(Object connection, String method, String uri, int contentLength, String content) throws Exception
                {
                    Socket client = (Socket)connection;
                    int port = ((ServerConnector)connector).getLocalPort();
                    String request = method + " " + uri + " HTTP/1.1\r\n" +
                        "Host: localhost:" + port + "\r\n" +
                        "Content-Type: plain/text\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "\r\n" +
                        content;
                    client.getOutputStream().write(request.getBytes());
                    client.getOutputStream().flush();
                }

                @Override
                public void send(Object connection, String content) throws Exception
                {
                    Socket client = (Socket)connection;
                    client.getOutputStream().write(content.getBytes());
                    client.getOutputStream().flush();
                }

                @Override
                public HttpTester.Response response(Object connection) throws Exception
                {
                    Socket client = (Socket)connection;
                    HttpTester.Response response = HttpTester.parseResponse(client.getInputStream());
                    return response;
                }

                @Override
                void waitForClosed(Object connection) throws Exception
                {
                    Socket client = (Socket)connection;
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while(!client.isClosed())
                    {
                        assertThat(System.nanoTime(), lessThan(end));
                        if (client.getInputStream().read() < 0)
                            break;
                        Thread.sleep(10);
                    }
                }

                @Override
                public String toString()
                {
                    return "HTTP/1.1";
                }
            },

            new Scenario()
            {
                @Override
                Connector newConnector(Server server)
                {
                    LocalConnector connector = new LocalConnector(server);
                    return connector;
                }

                @Override
                public Object connect() throws Exception
                {
                    try
                    {
                        LocalConnector local = (LocalConnector)connector;
                        LocalEndPoint client = local.connect();
                        return client;
                    }
                    catch (Throwable th)
                    {
                        Log.getRootLogger().ignore(th);
                    }
                    return null;
                }

                @Override
                public void request(Object connection, String method, String uri, int contentLength, String content) throws Exception
                {
                    LocalEndPoint client = (LocalEndPoint)connection;
                    String request = method + " " + uri + " HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: plain/text\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "\r\n" +
                        content;

                    new Thread(()-> client.addInput(request)).start();
                }

                @Override
                public void send(Object connection, String content) throws Exception
                {
                    LocalEndPoint client = (LocalEndPoint)connection;
                    new Thread(()-> client.addInput(content)).start();
                }

                @Override
                public HttpTester.Response response(Object connection) throws Exception
                {
                    LocalEndPoint client = (LocalEndPoint)connection;
                    HttpTester.Response response = HttpTester.parseResponse(client.getResponse());
                    return response;
                }

                @Override
                void waitForClosed(Object connection) throws Exception
                {
                    LocalEndPoint client = (LocalEndPoint)connection;
                    if (client.isOpen())
                        client.waitUntilClosed();
                }

                @Override
                public String toString()
                {
                    return "LOCAL";
                }
            }
        );
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGracefulIdle(Scenario scenario) throws Exception
    {
        scenario.start();

        Object connection = scenario.connect();
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.send(connection, "67890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();
        scenario.handler.complete();
        HttpTester.Response response = scenario.response(connection);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        scenario.shutdown();

        // Opening another connection will fail
        assertThat(scenario.connect(), nullValue());

        // Sending another request with fail one way or another
        try
        {
            scenario.request(connection, "POST", "/two", 10, "1234567890");
            response = scenario.response(connection);
            if (response != null)
                assertThat(response.getStatus(), is(503));
        }
        catch(Throwable th)
        {
            // expected
        }

        scenario.waitForClosed(connection);

        scenario.join();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGracefulReading(Scenario scenario) throws Exception
    {
        scenario.start();

        Object connection = scenario.connect();
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.send(connection, "67890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();
        scenario.handler.complete();
        HttpTester.Response response = scenario.response(connection);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Send a partial request
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.handler.handle();
        scenario.handler.consume();

        scenario.shutdown();

        // Opening another connection will fail
        assertThat(scenario.connect(), nullValue());

        // Outstanding request can complete
        scenario.send(connection, "67890");
        scenario.handler.commit();
        scenario.handler.complete();
        response = scenario.response(connection);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));
        scenario.checkLastResponse(response);

        // Sending another request with fail one way or another
        try
        {
            scenario.request(connection, "POST", "/last", 10, "1234567890");
            response = scenario.response(connection);
            if (response != null)
                assertThat(response.getStatus(), is(503));
        }
        catch(Throwable th)
        {
            // expected
        }

        scenario.waitForClosed(connection);
        scenario.join();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGracefulHandling(Scenario scenario) throws Exception
    {
        scenario.start();

        Object connection = scenario.connect();
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.send(connection, "67890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();
        scenario.handler.complete();
        HttpTester.Response response = scenario.response(connection);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Send a partial request
        scenario.request(connection, "POST", "/one", 10, "1234567890");
        scenario.handler.handle();
        scenario.handler.consume();

        scenario.shutdown();

        // Opening another connection will fail
        assertThat(scenario.connect(), nullValue());

        // Outstanding request can complete
        scenario.handler.commit();
        scenario.handler.complete();
        response = scenario.response(connection);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Sending another request with fail one way or another
        try
        {
            scenario.request(connection, "POST", "/last", 10, "1234567890");
            response = scenario.response(connection);
            if (response != null)
                assertThat(response.getStatus(), is(503));
        }
        catch(Throwable th)
        {
            // expected
        }

        scenario.waitForClosed(connection);

        scenario.join();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGracefulCommitted(Scenario scenario) throws Exception
    {
        scenario.start();

        Object connection = scenario.connect();
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.send(connection, "67890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();
        scenario.handler.complete();
        HttpTester.Response response = scenario.response(connection);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Send a partial request
        scenario.request(connection, "POST", "/one", 10, "1234567890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();

        scenario.shutdown();

        // Opening another connection will fail
        assertThat(scenario.connect(), nullValue());

        // Outstanding request can complete
        scenario.handler.complete();
        response = scenario.response(connection);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Sending another request with fail one way or another
        try
        {
            scenario.request(connection, "POST", "/last", 10, "1234567890");
            response = scenario.response(connection);
            if (response != null)
                assertThat(response.getStatus(), is(503));
        }
        catch(Throwable th)
        {
            // expected
        }

        scenario.waitForClosed(connection);

        scenario.join();
    }



    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGracefulContextStop(Scenario scenario) throws Exception
    {
        scenario.start();

        Object connection = scenario.connect();
        scenario.request(connection, "POST", "/one", 10, "12345");
        scenario.send(connection, "67890");
        scenario.handler.handle();
        scenario.handler.consume();
        scenario.handler.commit();
        scenario.handler.complete();
        HttpTester.Response response = scenario.response(connection);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        // Send a partial request
        scenario.request(connection, "POST", "/one", 10, "1234567890");
        scenario.handler.handle();
        scenario.handler.consume();

        scenario.context.shutdown();

        // Opening another connection can be done, but will get 503 during shutdown
        Object connection2 = scenario.connect();
        assertThat(connection2, notNullValue());
        scenario.request(connection2, "POST", "/other", 10, "1234567890");
        response = scenario.response(connection2);
        assertThat(response.getStatus(), is(503));

        // Outstanding request can complete
        scenario.handler.commit();
        scenario.handler.complete();
        response = scenario.response(connection);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("read 10/10"));

        scenario.context.stop();
        // Sending another request with fail with 404
        scenario.request(connection, "POST", "/last", 10, "1234567890");
        response = scenario.response(connection);
        assertThat(response.getStatus(), is(404));

        scenario.shutdown();
        scenario.waitForClosed(connection);
        scenario.join();
    }

    /**
     * Test completed writes during shutdown do not close output
     * @throws Exception on test failure
     */
    @Test
    public void testABShutdown() throws Exception
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
        try(Socket client = new Socket("127.0.0.1", port))
        {
            client.getOutputStream().write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + port + "\r\n" +
                    "\r\n"
            ).getBytes());
            client.getOutputStream().flush();

            while (!connector.isShutdown())
                Thread.sleep(10);

            handler.latchB.countDown();

            String response = IO.toString(client.getInputStream());
            assertThat(response, startsWith("HTTP/1.1 200 "));
            assertThat(response, containsString("Content-Length: 2"));
            assertThat(response, containsString("Connection: close"));
            assertThat(response, endsWith("ab"));
        }
        stopper.join();
    }


    public void testSlowClose(long stopTimeout, long closeDelay, Matcher<Long> stopTimeMatcher) throws Exception
    {
        Server server = new Server();
        server.setStopTimeout(stopTimeout);

        CountDownLatch closed = new CountDownLatch(1);
        ServerConnector connector = new ServerConnector(server, 2, 2, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector con, EndPoint endPoint)
            {
                // Slow closing connection
                HttpConnection conn = new HttpConnection(getHttpConfiguration(), con, endPoint, getHttpCompliance(), isRecordHttpComplianceViolations())
                {
                    @Override
                    public void close()
                    {
                        try
                        {
                            try
                            {
                                Thread.sleep(closeDelay);
                            }
                            catch (InterruptedException e)
                            {
                                // no op
                            }
                            finally
                            {
                                super.close();
                            }
                        }
                        catch (Exception e)
                        {
                            // e.printStackTrace();
                        }
                        finally
                        {
                            closed.countDown();
                        }
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
        }
        catch (Exception e)
        {
            assertTrue(stopTimeout > 0 && stopTimeout < closeDelay);
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
        }

        // onClose Thread interrupted or completed
        if (stopTimeout > 0)
            assertTrue(closed.await(1000, TimeUnit.MILLISECONDS));

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
        Log.getLogger(QueuedThreadPool.class).info("Expect some threads can't be stopped");
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
        Log.getLogger(QueuedThreadPool.class).warn("Expect some threads can't be stopped");
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

    static class TestHandler extends AbstractHandler
    {
        private Exchanger<Void> handle = new Exchanger<>();
        private Exchanger<Void> consume = new Exchanger<>();
        private Exchanger<Void> commit = new Exchanger<>();
        private Exchanger<Void> complete = new Exchanger<>();
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();

        private void advance(Exchanger<Void> exchanger)
        {
            try
            {
                exchanger.exchange(null);
            }
            catch(InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
        public void handle()
        {
            advance(handle);
        }

        public void consume()
        {
            advance(consume);
        }

        public void commit()
        {
            advance(commit);
        }

        public void complete()
        {
            advance(complete);
        }


        @Override
        public void handle(String target, Request baseRequest,
                           HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            try
            {
                handle.exchange(null);
                consume.exchange(null);
                int c = 0;
                int content_length = request.getContentLength();
                InputStream in = request.getInputStream();

                while (true)
                {
                    if (in.read() < 0)
                        break;
                    c++;
                }

                baseRequest.setHandled(true);
                response.setStatus(200);
                byte[] content0 = "read ".getBytes();
                byte[] content1 = String.format("%d/%d", c, content_length).getBytes();
                response.setContentLength(content0.length + content1.length);
                response.getOutputStream().write(content0);

                commit.exchange(null);
                response.flushBuffer();

                complete.exchange(null);
                response.getOutputStream().write(content1);
            }
            catch (Throwable th)
            {
                thrown.set(th);
            }
        }
    }
}
