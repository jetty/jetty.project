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

package org.eclipse.jetty.server.handler;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DefaultHandlerTest
{
    private Server server;
    private ServerConnector connector;
    private DefaultHandler handler;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handler = new DefaultHandler();
        server.setHandler(new HandlerList(contexts, handler));

        handler.setServeIcon(true);
        handler.setShowContexts(true);

        contexts.addHandler(new ContextHandler("/foo"));
        contexts.addHandler(new ContextHandler("/bar"));

        server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void testRoot() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request =
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            assertEquals("text/html;charset=UTF-8", response.get(HttpHeader.CONTENT_TYPE));

            String content = new String(response.getContentBytes(), StandardCharsets.UTF_8);
            assertThat(content, containsString("Contexts known to this server are:"));
            assertThat(content, containsString("/foo"));
            assertThat(content, containsString("/bar"));
        }
    }

    @Test
    public void testSomePath() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request =
                "GET /some/path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            assertEquals("text/html;charset=ISO-8859-1", response.get(HttpHeader.CONTENT_TYPE));

            String content = new String(response.getContentBytes(), StandardCharsets.ISO_8859_1);
            assertThat(content, not(containsString("Contexts known to this server are:")));
            assertThat(content, not(containsString("/foo")));
            assertThat(content, not(containsString("/bar")));
        }
    }

    @Test
    public void testFavIcon() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request =
                "GET /favicon.ico HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("image/x-icon", response.get(HttpHeader.CONTENT_TYPE));
        }
    }
}
