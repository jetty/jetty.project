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

package org.eclipse.jetty.server;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HostHeaderCustomizerTest
{
    @Test
    public void testFixedHostPort() throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        final String serverName = "test_server_name";
        final int serverPort = 23232;
        final String redirectPath = "/redirect";
        httpConfig.addCustomizer(new HostHeaderCustomizer(serverName, serverPort));
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Test "Host" header
                assertEquals(serverName + ":" + serverPort, request.getHeaders().get(HttpHeader.HOST));

                // Test "getHttpURI()"
                HttpURI httpURI = request.getHttpURI();
                assertEquals(serverName, httpURI.getHost());
                assertEquals(serverPort, httpURI.getPort());

                // Test Request.getServerName / Request.getServerPort
                assertEquals(serverName, Request.getServerName(request));
                assertEquals(serverPort, Request.getServerPort(request));

                // Issue redirect
                Response.sendRedirect(request, response, callback, redirectPath);
                return true;
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET / HTTP/1.0\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);

                    String location = response.get("location");
                    assertNotNull(location);
                    URI redirectURI = new URI(location);
                    assertEquals("http", redirectURI.getScheme());
                    assertEquals(redirectPath, redirectURI.getPath());
                    assertEquals(serverName + ":" + serverPort, redirectURI.getAuthority());
                }
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testHostPort() throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        final String serverName = "127.0.0.1";
        final String redirectPath = "/redirect";
        httpConfig.addCustomizer(new HostHeaderCustomizer());
        final ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Test "Host" header
                assertEquals(serverName + ":" + connector.getLocalPort(), request.getHeaders().get(HttpHeader.HOST));

                // Test "getHttpURI()"
                HttpURI httpURI = request.getHttpURI();
                assertEquals(serverName, httpURI.getHost());
                assertEquals(connector.getLocalPort(), httpURI.getPort());

                // Test Request.getServerName / Request.getServerPort
                assertEquals(serverName, Request.getServerName(request));
                assertEquals(connector.getLocalPort(), Request.getServerPort(request));

                // Issue redirect
                Response.sendRedirect(request, response, callback, redirectPath);
                return true;
            }
        });

        server.start();

        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET / HTTP/1.0\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);

                    String location = response.get("location");
                    assertNotNull(location);
                    URI redirectURI = new URI(location);
                    assertEquals("http", redirectURI.getScheme());
                    assertEquals(redirectPath, redirectURI.getPath());
                    assertEquals(serverName + ":" + connector.getLocalPort(), redirectURI.getAuthority());
                }
            }
        }
        finally
        {
            server.stop();
        }
    }
}
