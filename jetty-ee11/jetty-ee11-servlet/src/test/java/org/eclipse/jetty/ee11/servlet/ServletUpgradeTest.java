//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.StringUtil.CRLF;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletUpgradeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletUpgradeTest.class);

    private Server server;
    private int port;

    public void setUp(HttpServlet servlet) throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(servlet), "/");

        server.setHandler(contextHandler);

        server.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void upgradeTest() throws Exception
    {
        CompletableFuture<TestHttpUpgradeHandler> futureUpgradeHandler = new CompletableFuture<>();
        setUp(new HttpServlet()
        {
            public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                TestHttpUpgradeHandler handler = request.upgrade(TestHttpUpgradeHandler.class);
                futureUpgradeHandler.complete(handler);

                // The call to upgrade() automatically sets the required response status and headers.
                assertThat(response.getStatus(), equalTo(HttpStatus.SWITCHING_PROTOCOLS_101));
                assertThat(response.getHeader(HttpHeader.CONNECTION.asString()), equalTo(HttpHeader.UPGRADE.asString()));
                assertThat(response.getHeader(HttpHeader.UPGRADE.asString()), equalTo("YES"));

                // Assert that init has not been called yet.
                assertThat(handler.initLatch.getCount(), equalTo(1L));
            }
        });

        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(0);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        String request = "POST /TestServlet HTTP/1.1" + CRLF +
            "Host: localhost:" + port + CRLF +
            "Upgrade: YES" + CRLF +
            "Connection: Upgrade" + CRLF +
            CRLF;

        output.write(request.getBytes());
        writeChunk(output, "Hello");
        writeChunk(output, "World");
        output.flush();

        StringBuffer sb = new StringBuffer();
        CompletableFuture<String> futureContent = new CompletableFuture<>();
        new Thread(() ->
        {
            LOG.info("Consuming the response from the server");
            try
            {
                while (true)
                {
                    int read = input.read();
                    if (read == -1)
                        break;
                    sb.append((char)read);
                }
                futureContent.complete(sb.toString());
            }
            catch (Throwable t)
            {
                LOG.warn("failed with content: " + sb, t);
                futureContent.completeExceptionally(t);
            }
        }).start();

        // Wait until we get the echoed content.
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofMillis(200))
            .until(() -> sb.toString().contains("HelloWorld"));

        // The destroy latch is only counted down after the connection is closed.
        TestHttpUpgradeHandler handler = futureUpgradeHandler.get(5, TimeUnit.SECONDS);
        assertThat(handler.destroyLatch.getCount(), equalTo(1L));
        socket.shutdownOutput();
        assertTrue(handler.destroyLatch.await(5, TimeUnit.SECONDS));

        String fullContent = futureContent.get(5, TimeUnit.SECONDS);
        assertThat(fullContent, containsString("HTTP/1.1 101 Switching Protocols"));
        assertThat(fullContent, containsString("Connection: Upgrade"));
        assertThat(fullContent, containsString("Upgrade: YES"));
        assertThat(fullContent, containsString("""
            TCKHttpUpgradeHandler.init\r
            =onDataAvailable\r
            HelloWorld\r
            =onAllDataRead\r
            """));

        socket.close();
        assertTrue(handler.destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEarlyEof() throws Exception
    {
        CompletableFuture<TestHttpUpgradeHandler> futureUpgradeHandler = new CompletableFuture<>();
        setUp(new HttpServlet()
        {
            public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                TestHttpUpgradeHandler handler = request.upgrade(TestHttpUpgradeHandler.class);
                futureUpgradeHandler.complete(handler);

                // The call to upgrade() automatically sets the required response status and headers.
                assertThat(response.getStatus(), equalTo(HttpStatus.SWITCHING_PROTOCOLS_101));
                assertThat(response.getHeader(HttpHeader.CONNECTION.asString()), equalTo(HttpHeader.UPGRADE.asString()));
                assertThat(response.getHeader(HttpHeader.UPGRADE.asString()), equalTo("YES"));

                // Assert that init has not been called yet.
                assertThat(handler.initLatch.getCount(), equalTo(1L));
            }
        });

        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(0);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        String request = "POST /TestServlet HTTP/1.1" + CRLF +
            "Host: localhost:" + port + CRLF +
            "Upgrade: YES" + CRLF +
            "Connection: Upgrade" + CRLF +
            CRLF;

        output.write(request.getBytes());
        writeChunk(output, "Hello");
        writeChunk(output, "World");
        output.flush();

        StringBuffer sb = new StringBuffer();
        CompletableFuture<String> futureContent = new CompletableFuture<>();
        new Thread(() ->
        {
            LOG.info("Consuming the response from the server");
            try
            {
                while (true)
                {
                    int read = input.read();
                    if (read == -1)
                        break;
                    sb.append((char)read);
                }
                futureContent.complete(sb.toString());
            }
            catch (Throwable t)
            {
                futureContent.completeExceptionally(t);
            }
        }).start();

        // Wait until we get the echoed content.
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofMillis(200)).until(() -> sb.toString().contains("HelloWorld"));
        String content = sb.toString();
        assertThat(content, containsString("HTTP/1.1 101 Switching Protocols"));
        assertThat(content, containsString("Connection: Upgrade"));
        assertThat(content, containsString("Upgrade: YES"));
        assertThat(content, containsString("""
            TCKHttpUpgradeHandler.init\r
            =onDataAvailable\r
            HelloWorld"""));

        // The HttpUpgradeHandler.destroy() should still be called in case of an error.
        socket.setSoLinger(true, 0);
        socket.close();
        TestHttpUpgradeHandler handler = futureUpgradeHandler.get(5, TimeUnit.SECONDS);
        assertTrue(handler.destroyLatch.await(5, TimeUnit.SECONDS));

        ExecutionException exception = assertThrows(ExecutionException.class, () -> futureContent.get(5, TimeUnit.SECONDS));
        assertThat(exception.getCause(), instanceOf(SocketException.class));
        assertThat(exception.getCause().getMessage(), containsString("Socket closed"));

        Throwable throwable = handler.errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(throwable, instanceOf(EofException.class));
    }

    public static class TestHttpUpgradeHandler implements HttpUpgradeHandler
    {
        public CountDownLatch initLatch = new CountDownLatch(1);
        public CountDownLatch destroyLatch = new CountDownLatch(1);
        public CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();

        public TestHttpUpgradeHandler()
        {
        }

        @Override
        public void destroy()
        {
            destroyLatch.countDown();
        }

        @Override
        public void init(WebConnection wc)
        {
            try
            {
                ServletInputStream input = wc.getInputStream();
                ServletOutputStream output = wc.getOutputStream();
                TestReadListener readListener = new TestReadListener(this, input, output);
                input.setReadListener(readListener);
                output.println("TCKHttpUpgradeHandler.init");
                output.flush();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
            finally
            {
                initLatch.countDown();
            }
        }

        public void onError(Throwable t)
        {
            errorFuture.complete(t);
        }
    }

    private static class TestReadListener implements ReadListener
    {
        private final TestHttpUpgradeHandler upgradeHandler;
        private final ServletInputStream input;
        private final ServletOutputStream output;
        private boolean outputOnDataAvailable = false;

        TestReadListener(TestHttpUpgradeHandler upgradeHandler, ServletInputStream in, ServletOutputStream out)
        {
            this.upgradeHandler = upgradeHandler;
            input = in;
            output = out;
        }

        @Override
        public void onAllDataRead()
        {
            try
            {
                output.println("\r\n=onAllDataRead");
                output.close();
            }
            catch (Exception ex)
            {
                upgradeHandler.onError(ex);
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void onDataAvailable()
        {
            try
            {
                if (!outputOnDataAvailable)
                {
                    outputOnDataAvailable = true;
                    output.println("=onDataAvailable");
                }

                StringBuilder sb = new StringBuilder();
                int len;
                byte[] b = new byte[1024];
                while (input.isReady() && (len = input.read(b)) != -1)
                {
                    String data = new String(b, 0, len);
                    sb.append(data);
                }
                output.print(sb.toString());
                output.flush();
            }
            catch (Exception ex)
            {
                upgradeHandler.onError(ex);
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void onError(final Throwable t)
        {
            upgradeHandler.onError(t);
        }
    }

    private static void writeChunk(OutputStream out, String data) throws IOException
    {
        if (data != null)
        {
            out.write(data.getBytes());
        }
        out.flush();
    }
}
