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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SSLAsyncIOServletTest
{
    public static Stream<Arguments> scenarios()
    {
        ArrayList<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new NormalScenario());
        scenarios.add(new SslScenario());
        return scenarios.stream().map(Arguments::of);
    }

    private Scenario activeScenario;

    private void prepare(Scenario scenario, HttpServlet servlet) throws Exception
    {
        activeScenario = scenario;
        scenario.start(servlet);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        activeScenario.stop();
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncIOWritesWithAggregation(Scenario scenario) throws Exception
    {
        Random random = new Random();
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final byte[] content = new byte[50000];
        for (int i = 0; i < content.length; ++i)
        {
            content[i] = (byte)chars.charAt(random.nextInt(chars.length()));
        }

        prepare(scenario, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                final AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                final int bufferSize = 4096;
                response.setBufferSize(bufferSize);
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    private int writes;
                    private int written;

                    @Override
                    public void onWritePossible() throws IOException
                    {
                        ServletOutputStream output = asyncContext.getResponse().getOutputStream();
                        do
                        {
                            int toWrite = content.length - written;
                            if (toWrite == 0)
                            {
                                asyncContext.complete();
                                return;
                            }

                            toWrite = Math.min(toWrite, bufferSize);

                            // Perform a write that is smaller than the buffer size to
                            // trigger the condition where the bytes are aggregated.
                            if (writes == 1)
                                toWrite -= 16;

                            output.write(content, written, toWrite);
                            ++writes;
                            written += toWrite;
                        }
                        while (output.isReady());
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        asyncContext.complete();
                    }
                });
            }
        });

        try (Socket client = scenario.newClient())
        {
            String request =
                "GET " + scenario.getServletPath() + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            InputStream inputStream = client.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(inputStream);
            assertEquals(200, response.getStatus());
            assertArrayEquals(content, response.getContent().getBytes("UTF-8"));
        }
    }

    public interface Scenario
    {
        String getServletPath();

        void start(HttpServlet servlet) throws Exception;

        Socket newClient() throws IOException;

        void stop() throws Exception;
    }

    public static class NormalScenario implements Scenario
    {
        private Server server;
        private ServerConnector connector;
        private String contextPath;
        private String servletPath;

        @Override
        public String getServletPath()
        {
            return contextPath + servletPath;
        }

        public void start(HttpServlet servlet) throws Exception
        {
            server = new Server();

            connector = new ServerConnector(server);
            server.addConnector(connector);

            contextPath = "/context";
            ServletContextHandler context = new ServletContextHandler(server, contextPath, true, false);
            servletPath = "/servlet";
            context.addServlet(new ServletHolder(servlet), servletPath);

            server.start();
        }

        @Override
        public Socket newClient() throws IOException
        {
            return new Socket("localhost", connector.getLocalPort());
        }

        @Override
        public void stop() throws Exception
        {
            server.stop();
        }
    }

    public static class SslScenario implements Scenario
    {
        private Server server;
        private ServerConnector connector;
        private SslContextFactory.Server sslContextFactory;
        private String contextPath;
        private String servletPath;

        @Override
        public String getServletPath()
        {
            return contextPath + servletPath;
        }

        public void start(HttpServlet servlet) throws Exception
        {
            Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12");

            sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(keystorePath.toString());
            sslContextFactory.setKeyStorePassword("storepwd");

            server = new Server();

            connector = new ServerConnector(server, sslContextFactory);
            server.addConnector(connector);

            contextPath = "/context";
            ServletContextHandler context = new ServletContextHandler(server, contextPath, true, false);
            servletPath = "/servlet";
            context.addServlet(new ServletHolder(servlet), servletPath);

            server.start();
        }

        @Override
        public Socket newClient() throws IOException
        {
            return sslContextFactory.getSslContext().getSocketFactory().createSocket("localhost", connector.getLocalPort());
        }

        @Override
        public void stop() throws Exception
        {
            server.stop();
        }
    }
}
