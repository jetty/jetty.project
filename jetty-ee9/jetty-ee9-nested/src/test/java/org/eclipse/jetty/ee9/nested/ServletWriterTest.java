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

package org.eclipse.jetty.ee9.nested;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletWriterTest
{
    private Server _server;
    private ContextHandler _context;
    private ServerConnector _connector;

    private void start(int aggregationSize, Handler handler) throws Exception
    {
        _server = new Server();
        _context = new ContextHandler(_server);
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setOutputBufferSize(2 * aggregationSize);
        httpConfig.setOutputAggregationSize(2 * aggregationSize);
        _connector = new ServerConnector(_server, 1, 1, new HttpConnectionFactory(httpConfig));
        _server.addConnector(_connector);
        _context.setHandler(handler);
        _server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testTCPCongestedCloseDoesNotDeadlock() throws Exception
    {
        // Write a large content so it gets TCP congested when calling close().
        char[] chars = new char[128 * 1024 * 1024];
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> serverThreadRef = new AtomicReference<>();
        start(chars.length, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                serverThreadRef.set(Thread.currentThread());
                jettyRequest.setHandled(true);
                response.setContentType("text/plain; charset=utf-8");
                PrintWriter writer = response.getWriter();
                Arrays.fill(chars, '0');
                // The write is entirely buffered.
                writer.write(chars);
                latch.countDown();
                // Closing will trigger the write over the network.
                writer.close();
            }
        });

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(UTF_8));
            output.flush();

            // Wait until the response is buffered, so close() will write it.
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // Don't read the response yet to trigger TCP congestion.
            Thread.sleep(1000);

            // Now read the response.
            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8));
            String line = reader.readLine();
            assertThat(line, containsString(" 200 "));
            // Consume all the content, we should see EOF.
            while (line != null)
            {
                line = reader.readLine();
            }
        }
        catch (Throwable x)
        {
            Thread thread = serverThreadRef.get();
            if (thread != null)
                thread.interrupt();
            throw x;
        }
    }
}
