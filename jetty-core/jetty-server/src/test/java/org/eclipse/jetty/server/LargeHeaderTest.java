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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LargeHeaderTest
{
    private Server server;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        HttpConnectionFactory http = new HttpConnectionFactory(config);

        ServerConnector connector = new ServerConnector(server, http);
        connector.setPort(0);
        connector.setIdleTimeout(5000);
        server.addConnector(connector);

        server.setErrorHandler(new ErrorHandler());

        server.setHandler(new AbstractHandler()
        {
            final String largeHeaderValue;

            {
                byte[] bytes = new byte[8 * 1024];
                Arrays.fill(bytes, (byte)'X');
                largeHeaderValue = "LargeHeaderOver8k-" + new String(bytes, UTF_8) + "_Z_";
            }

            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setHeader(HttpHeader.CONTENT_TYPE.toString(), MimeTypes.Type.TEXT_HTML.toString());
                response.setHeader("LongStr", largeHeaderValue);
                PrintWriter writer = response.getWriter();
                writer.write("<html><h1>FOO</h1></html>");
                writer.flush();
                response.flushBuffer();
                baseRequest.setHandled(true);
            }
        });
        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testLargeHeader() throws Throwable
    {
        ExecutorService executorService = Executors.newFixedThreadPool(8);

        int localPort = server.getURI().getPort();
        String rawRequest = "GET / HTTP/1.1\r\n" +
            "Host: localhost:" + localPort + "\r\n" +
            "\r\n";

        Throwable issues = new Throwable();

        for (int i = 0; i < 500; ++i)
        {
            executorService.submit(() ->
            {
                try (Socket client = new Socket("localhost", localPort);
                     OutputStream output = client.getOutputStream();
                     InputStream input = client.getInputStream())
                {
                    output.write(rawRequest.getBytes(UTF_8));
                    output.flush();

                    String rawResponse = IO.toString(input, UTF_8);
                    HttpTester.Response response = HttpTester.parseResponse(rawResponse);
                    assertThat(response.getStatus(), is(500));
                }
                catch (Throwable t)
                {
                    issues.addSuppressed(t);
                }
            });
        }

        executorService.awaitTermination(5, TimeUnit.SECONDS);
        if (issues.getSuppressed().length > 0)
        {
            throw issues;
        }
    }
}
