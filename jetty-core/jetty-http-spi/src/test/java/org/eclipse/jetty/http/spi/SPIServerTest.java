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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SPIServerTest
{
    static
    {
        LoggingUtil.init();
    }

    String host = "localhost";
    HttpServer server;
    int port;

    @BeforeEach
    public void before() throws Exception
    {
        server = new JettyHttpServerProvider().createHttpServer(new InetSocketAddress(host, 0), 10);

        server.start();
        port = server.getAddress().getPort();
        System.err.println(port);
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop(0);
    }

    @Test
    public void testSimple() throws Exception
    {
        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                OutputStream responseBody = exchange.getResponseBody();
                responseBody.write("Hello".getBytes(StandardCharsets.ISO_8859_1));
                responseBody.close();
            }
        });

        URL url = new URL("http://localhost:" + port + "/");
        assertThat(IO.toString(url.openConnection().getInputStream()), is("Hello"));
    }

    @Test
    public void testAuth() throws Exception
    {
        final HttpContext httpContext = server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                OutputStream responseBody = exchange.getResponseBody();
                responseBody.write("Hello".getBytes(StandardCharsets.ISO_8859_1));
                responseBody.close();
            }
        });

        httpContext.setAuthenticator(new BasicAuthenticator("Test")
        {
            @Override
            public boolean checkCredentials(String username, String password)
            {
                if ("username".equals(username) && password.equals("password"))
                    return true;
                return false;
            }
        });

        URL url = new URL("http://localhost:" + port + "/");
        HttpURLConnection client = (HttpURLConnection)url.openConnection();
        client.connect();
        assertThat(client.getResponseCode(), is(401));

        Authenticator.setDefault(new Authenticator()
        {
            protected PasswordAuthentication getPasswordAuthentication()
            {
                return new PasswordAuthentication("username", "password".toCharArray());
            }
        });

        client = (HttpURLConnection)url.openConnection();
        String userpass = "username:password";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(userpass.getBytes(StandardCharsets.ISO_8859_1));
        client.setRequestProperty("Authorization", basicAuth);

        client.connect();
        assertThat(client.getResponseCode(), is(200));
        assertThat(IO.toString(client.getInputStream()), is("Hello"));
    }
}
