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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.StringUtil.CRLF;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletUpgradeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletUpgradeTest.class);

    private Server server;
    private int port;
    private static CountDownLatch destroyLatch;

    @BeforeEach
    public void setUp() throws Exception
    {
        destroyLatch = new CountDownLatch(1);

        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new TestServlet()), "/TestServlet");

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
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(0);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        String request = "POST /TestServlet HTTP/1.1" + CRLF +
            "Host: localhost:" + port + CRLF +
            "Accept: text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2" + CRLF +
            "Upgrade: YES" + CRLF +
            "Connection: Upgrade" + CRLF +
            "Content-type: application/x-www-form-urlencoded" + CRLF +
            CRLF;

        output.write(request.getBytes());
        writeChunk(output, "Hello");
        writeChunk(output, "World");
        output.flush();
        socket.shutdownOutput();

        CompletableFuture<String> futureContent = new CompletableFuture<>();
        new Thread(() ->
        {
            LOG.info("Consuming the response from the server");
            Utf8StringBuilder sb = new Utf8StringBuilder();
            try
            {
                while (true)
                {
                    int read = input.read();
                    if (read == -1)
                        break;
                    sb.append((byte)read);
                }
                futureContent.complete(sb.toCompleteString());
            }
            catch (Throwable t)
            {
                LOG.warn("failed with content: " + sb, t);
                futureContent.completeExceptionally(t);
            }

        }).start();

//        socket.close();

        // TODO test for the 101 response.
        String content = futureContent.get(500000, TimeUnit.SECONDS);
        String expectedContent = """
            TCKHttpUpgradeHandler.init\r
            =onDataAvailable\r
            HelloWorld\r
            =onAllDataRead\r
            """;
        assertThat(content, endsWith(expectedContent));

        input.close();
        output.close();
        socket.close();
        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    private static class TestServlet extends HttpServlet
    {
        public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getHeader("Upgrade") != null)
            {
                response.setStatus(101);
                response.setHeader("Upgrade", "YES");
                response.setHeader("Connection", "Upgrade");
                TestHttpUpgradeHandler handler = request.upgrade(TestHttpUpgradeHandler.class);
                assertThat(handler, instanceOf(TestHttpUpgradeHandler.class));
            }
            else
            {
                response.getWriter().println("No upgrade");
                response.getWriter().println("End of Test");
            }
        }
    }

    public static class TestHttpUpgradeHandler implements HttpUpgradeHandler
    {
        public TestHttpUpgradeHandler()
        {
        }

        @Override
        public void destroy()
        {
            System.err.println("destroy");
            destroyLatch.countDown();
        }

        @Override
        public void init(WebConnection wc)
        {
            try
            {
                ServletInputStream input = wc.getInputStream();
                ServletOutputStream output = wc.getOutputStream();
                TestReadListener readListener = new TestReadListener(wc, input, output);
                input.setReadListener(readListener);
                output.println("TCKHttpUpgradeHandler.init");
                output.flush();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }
    }

    private static class TestReadListener implements ReadListener
    {
        private final WebConnection wc;
        private final ServletInputStream input;
        private final ServletOutputStream output;
        private boolean outputOnDataAvailable = false;

        TestReadListener(WebConnection wc, ServletInputStream in, ServletOutputStream out)
        {
            this.wc = wc;
            input = in;
            output = out;
        }

        @Override
        public void onAllDataRead()
        {
            try
            {
                System.err.println("onAllDataRead");
                output.println("\r\n=onAllDataRead");
                output.close();
            }
            catch (Exception ex)
            {
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
                    System.err.println("len: " + len);
                }
                output.print(sb.toString());
                output.flush();
            }
            catch (Exception ex)
            {
                System.err.println("onDataAvailable " + ex);
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void onError(final Throwable t)
        {
            LOG.error("TestReadListener error", t);
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
