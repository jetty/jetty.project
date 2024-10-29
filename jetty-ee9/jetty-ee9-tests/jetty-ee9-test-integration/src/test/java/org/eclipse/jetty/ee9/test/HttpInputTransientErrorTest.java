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

package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

//TODO test all protocols
public class HttpInputTransientErrorTest
{
    private static final int IDLE_TIMEOUT = 250;

    private LocalConnector connector;
    private Server server;
    private ArrayByteBufferPool.Tracking bufferPool;

    @AfterEach
    public void tearDown()
    {
        try
        {
            if (bufferPool != null)
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    private void startServer(HttpServlet servlet) throws Exception
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(null, null, bufferPool);
        connector = new LocalConnector(server, new HttpConnectionFactory());
        connector.setIdleTimeout(IDLE_TIMEOUT);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/ctx");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, "/*");

        server.start();
    }

    @Test
    public void testAsyncServletTimeoutErrorIsTerminal() throws Exception
    {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                AsyncContext asyncContext = req.startAsync(req, resp);
                asyncContext.setTimeout(0);
                resp.setContentType("text/plain;charset=UTF-8");

                req.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {

                    }

                    @Override
                    public void onAllDataRead()
                    {

                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        failures.add(t);
                        try
                        {
                            ServletInputStream input = req.getInputStream();
                            if (!input.isReady())
                            {
                                resp.setStatus(597);
                                asyncContext.complete();
                                return;
                            }
                            try
                            {
                                input.read();
                                resp.setStatus(598);
                                asyncContext.complete();
                                return;
                            }
                            catch (IOException e)
                            {
                                failures.add(e);
                            }

                            resp.setStatus(HttpStatus.OK_200);
                            asyncContext.complete();
                        }
                        catch (IOException e)
                        {
                            resp.setStatus(599);
                            e.printStackTrace();
                            asyncContext.complete();
                        }
                    }
                });
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(failures.size(), is(2));
            assertInstanceOf(TimeoutException.class, failures.get(0));
            assertInstanceOf(IOException.class, failures.get(1));
            assertThat(failures.get(1).getCause(), sameInstance(failures.get(0)));
        }
    }

    @Test
    public void testBlockingServletTimeoutErrorIsTerminal() throws Exception
    {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    IO.toString(req.getInputStream());
                }
                catch (IOException e)
                {
                    failures.add(e);
                }
                try
                {
                    IO.toString(req.getInputStream());
                }
                catch (IOException e)
                {
                    failures.add(e);
                }

                resp.setStatus(HttpStatus.OK_200);
            }
        });

        try (LocalConnector.LocalEndPoint localEndPoint = connector.connect())
        {
            String request = """
                POST /ctx/post HTTP/1.1
                Host: local
                Content-Length: 10
                            
                """;
            localEndPoint.addInput(request);
            Thread.sleep((long)(IDLE_TIMEOUT * 1.5));
            localEndPoint.addInput("1234567890");
            HttpTester.Response response = HttpTester.parseResponse(localEndPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertThat("Unexpected response status\n" + response + response.getContent(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(failures.size(), is(2));
            assertInstanceOf(IOException.class, failures.get(0));
            assertInstanceOf(TimeoutException.class, failures.get(0).getCause());
            assertInstanceOf(IOException.class, failures.get(1));
            assertInstanceOf(TimeoutException.class, failures.get(1).getCause());
        }
    }
}
