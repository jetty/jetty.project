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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.util.component.LifeCycle;
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

        HttpClient client = new HttpClient();
        try
        {
            client.start();

            Request request = client.newRequest("localhost", port)
                .scheme("http")
                .method(HttpMethod.GET)
                .path("/");

            ContentResponse response = request.send();

            assertThat(response.getStatus(), is(200));
            assertThat(response.getHeaders().get("Content-Type"), is("text/plain"));
            String body = response.getContentAsString();
            assertThat(body, is("Hello"));
        }
        finally
        {
            LifeCycle.stop(client);
        }
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

        HttpClient client = new HttpClient();
        try
        {
            client.start();

            Request request = client.newRequest("localhost", port)
                .scheme("http")
                .method(HttpMethod.GET)
                .path("/");

            ContentResponse response = request.send();

            assertThat(response.getStatus(), is(200));
            assertThat(response.getHeaders().get("Content-Type"), is(mediaType));
            String body = response.getContentAsString();
            assertThat(body, is("Hello"));
        }
        finally
        {
            LifeCycle.stop(client);
        }
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

        HttpClient client = new HttpClient();
        try
        {
            client.start();

            Request request = client.newRequest("localhost", port)
                .scheme("http")
                .method(HttpMethod.GET)
                .headers((headers) ->
                    headers.put("Content-Type", mediaType))
                .path("/");

            ContentResponse response = request.send();

            assertThat(response.getStatus(), is(200));
            String body = response.getContentAsString();
            assertThat(body, is(mediaType));
        }
        finally
        {
            LifeCycle.stop(client);
        }
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
    public void testRequestUserAgentHeader() throws Exception
    {
        // The `User-Agent` header that should ignore value list behaviors
        final String ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";
        // The `Sec-Ch-Ua` header that should participate in value list behaviors
        final String secua = "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"\n";

        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                Headers requestheaders = exchange.getRequestHeaders();

                try (OutputStream responseBody = exchange.getResponseBody();
                     PrintStream out = new PrintStream(responseBody, true, StandardCharsets.UTF_8))
                {
                    // A `User-Agent` header, that should skip value list behaviors
                    String useragent = requestheaders.getFirst("User-Agent");
                    out.printf("User-Agent: %s%n", useragent);

                    // A `Sec-Ch-Ua` header, that should result in a value list
                    List<String> secuseragent = requestheaders.get("Sec-Ch-Ua");
                    int i = 0;
                    for (String value : secuseragent)
                    {
                        out.printf("Sec-Ch-Ua[%d]: %s%n", i++, value);
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
                    User-Agent: %s
                    Sec-Ch-Ua: %s
                                        
                    """.formatted(ua, secua);

                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Input input = HttpTester.from(socket.getInputStream());
                HttpTester.Response response = HttpTester.parseResponse(input);
                assertThat(response.getStatus(), is(200));
                String body = response.getContent();
                assertThat(body, containsString("User-Agent: " + ua));
                assertThat(body, containsString("Sec-Ch-Ua[0]: \"Chromium\";v=\"116\""));
                assertThat(body, containsString("Sec-Ch-Ua[1]: \"Not)A;Brand\";v=\"24\""));
                assertThat(body, containsString("Sec-Ch-Ua[2]: \"Google Chrome\";v=\"116\""));
            }
        }
    }

    @Test
    public void testIfModifiedSinceHeader() throws Exception
    {
        final String since = "Sat, 29 Oct 1994 19:43:31 GMT";

        server.createContext("/", new HttpHandler()
        {
            public void handle(HttpExchange exchange) throws IOException
            {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                Headers requestheaders = exchange.getRequestHeaders();

                try (OutputStream responseBody = exchange.getResponseBody();
                     PrintStream out = new PrintStream(responseBody, true, StandardCharsets.UTF_8))
                {
                    // A `If-Modified-Since` header, that should skip value list behaviors
                    String ifmodified = requestheaders.getFirst("If-Modified-Since");
                    out.printf("If-Modified-Since: %s%n", ifmodified);
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
                    If-Modified-Since: %s
                                        
                    """.formatted(since);

                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Input input = HttpTester.from(socket.getInputStream());
                HttpTester.Response response = HttpTester.parseResponse(input);
                assertThat(response.getStatus(), is(200));
                String body = response.getContent();
                assertThat(body, containsString("If-Modified-Since: " + since));
            }
        }
    }

    @Test
    public void testAuth() throws Exception
    {
        final String testRealm = "Test";
        final String testUsername = "username";
        final String testPassword = "password";

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

        httpContext.setAuthenticator(new BasicAuthenticator(testRealm)
        {
            @Override
            public boolean checkCredentials(String username, String password)
            {
                if (testUsername.equals(username) && testPassword.equals(password))
                    return true;
                return false;
            }
        });

        HttpClient client = new HttpClient();
        try
        {
            client.start();

            Request request = client.newRequest("localhost", port)
                .scheme("http")
                .method(HttpMethod.GET)
                .path("/");

            ContentResponse response = request.send();
            assertThat(response.getStatus(), is(401));

            request = client.newRequest("localhost", port)
                .scheme("http")
                .method(HttpMethod.GET)
                .path("/");

            AuthenticationStore store = client.getAuthenticationStore();

            URI uri = URI.create("http://localhost:" + port + "/");
            store.addAuthentication(new BasicAuthentication(uri, testRealm, testUsername, testPassword));

            response = request.send();
            assertThat(response.getStatus(), is(200));
            String body = response.getContentAsString();
            assertThat(body, is("Hello"));
        }
        finally
        {
            LifeCycle.stop(client);
        }
    }
}
