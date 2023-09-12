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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        HttpURLConnection http = (HttpURLConnection)url.openConnection();
        assertThat(http.getResponseCode(), is(200));
        assertThat(http.getHeaderField("Content-Type"), is("text/plain"));
        String body = IO.toString(http.getInputStream());
        assertThat(body, is("Hello"));
    }

    @Test
    public void testComplexResponseMediaType() throws Exception
    {
        final String mediaType = "multipart/related;" +
            "start=\"<a*b-c-d-e-f@example.com>\";" +
            "type=\"application/xop+xml\";" +
            "boundary=\"uuid:aa-bb-cc-dd-ee\";" +
            "start-info=\"application/soap+xml;action=\\\"urn:xyz\\\"\"";

        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", mediaType);
                exchange.sendResponseHeaders(200, 0);

                OutputStream responseBody = exchange.getResponseBody();
                responseBody.write("Hello".getBytes(StandardCharsets.ISO_8859_1));
                responseBody.close();
            }
        });

        URL url = new URL("http://localhost:" + port + "/");
        HttpURLConnection http = (HttpURLConnection)url.openConnection();
        assertThat(http.getResponseCode(), is(200));
        assertThat(http.getHeaderField("Content-Type"), is(mediaType));
        String body = IO.toString(http.getInputStream());
        assertThat(body, is("Hello"));
    }

    @Test
    public void testComplexRequestMediaType() throws Exception
    {
        final String mediaType = "multipart/related;" +
            "start=\"<a*b-c-d-e-f@example.com>\";" +
            "type=\"application/xop+xml\";" +
            "boundary=\"uuid:aa-bb-cc-dd-ee\";" +
            "start-info=\"application/soap+xml;action=\\\"urn:xyz\\\"\"";

        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                Headers requestHeaders = exchange.getRequestHeaders();
                List<String> mediaTypeValue = requestHeaders.get("Content-Type");

                try (OutputStream responseBody = exchange.getResponseBody();
                    PrintStream out = new PrintStream(responseBody, true, StandardCharsets.UTF_8))
                {
                    // should only have 1 entry, but joining together multiple in case of bad impl
                    out.print(String.join(",", mediaTypeValue));
                }
            }
        });

        URL url = new URL("http://localhost:" + port + "/");
        HttpURLConnection http = (HttpURLConnection)url.openConnection();
        http.setRequestProperty("Content-Type", mediaType);
        assertThat(http.getResponseCode(), is(200));
        String body = IO.toString(http.getInputStream());
        assertThat(body, is(mediaType));
    }

    @Test
    public void testMultipleRequestHeaderMerging() throws Exception
    {
        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                Headers requestHeaders = exchange.getRequestHeaders();

                try (OutputStream responseBody = exchange.getResponseBody();
                     PrintStream out = new PrintStream(responseBody, true, StandardCharsets.UTF_8))
                {
                    for (String name : requestHeaders.keySet().stream().sorted().toList())
                    {
                        out.printf("%s: %s%n", name, String.join(",", requestHeaders.get(name)));
                    }
                }
            }
        });

        // Sending this in the raw, as HttpURLConnection will not send multiple request headers with the same name
        try (Socket socket = new Socket("localhost", port))
        {
            try (OutputStream output = socket.getOutputStream())
            {
                String request = """
                    GET / HTTP/1.1
                    Host: localhost
                    Connection: close
                    X-Action: Begin
                    X-Action: "Ongoing Behavior"
                    X-Action: Final
                    
                    """;

                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Input input = HttpTester.from(socket.getInputStream());
                HttpTester.Response response = HttpTester.parseResponse(input);
                assertThat(response.getStatus(), is(200));
                assertThat(response.getContent(), containsString("X-action: Begin,\"Ongoing Behavior\",Final"));
            }
        }
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
