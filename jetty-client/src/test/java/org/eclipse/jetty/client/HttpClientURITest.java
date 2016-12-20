//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientURITest extends AbstractHttpClientServerTest
{
    public HttpClientURITest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testIPv6Host() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "::1";
        Request request = client.newRequest(host, connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS);

        Assert.assertEquals(host, request.getHost());
        StringBuilder uri = new StringBuilder();
        URIUtil.appendSchemeHostPort(uri, scheme, host, connector.getLocalPort());
        Assert.assertEquals(uri.toString(), request.getURI().toString());

        Assert.assertEquals(HttpStatus.OK_200, request.send().getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIDNHost() throws Exception
    {
        startClient();
        client.newRequest(scheme + "://пример.рф"); // example.com-like host in IDN domain
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIDNRedirect() throws Exception
    {
        // Internationalized Domain Name.
        String exampleHost = scheme + "://пример.рф";
        String incorrectlyDecoded = new String(exampleHost.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        // Simple server that only parses clear-text HTTP/1.1.
        IDNRedirectServer server = new IDNRedirectServer(exampleHost);
        server.start();

        try
        {
            startClient();

            ContentResponse response = client.newRequest("localhost", server.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .send();

            HttpField location = response.getHeaders().getField(HttpHeader.LOCATION);
            Assert.assertEquals(incorrectlyDecoded, location.getValue());

            try
            {
                client.newRequest("localhost", server.getLocalPort())
                        .timeout(5, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .send();
            }
            catch (ExecutionException x)
            {
                Throwable cause = x.getCause();
                if (cause instanceof IllegalArgumentException)
                    throw (IllegalArgumentException)cause;
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testPath() throws Exception
    {
        final String path = "/path";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(path);

        Assert.assertEquals(path, request.getPath());
        Assert.assertNull(request.getQuery());
        Fields params = request.getParams();
        Assert.assertEquals(0, params.getSize());
        Assert.assertTrue(request.getURI().toString().endsWith(path));

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testPathWithQuery() throws Exception
    {
        String name = "a";
        String value = "1";
        final String query = name + "=" + value;
        final String path = "/path";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        String pathQuery = path + "?" + query;
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(pathQuery);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(1, params.getSize());
        Assert.assertEquals(value, params.get(name).getValue());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testPathWithParam() throws Exception
    {
        String name = "a";
        String value = "1";
        final String query = name + "=" + value;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(path)
                .param(name, value);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(1, params.getSize());
        Assert.assertEquals(value, params.get(name).getValue());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testPathWithQueryAndParam() throws Exception
    {
        String name1 = "a";
        String value1 = "1";
        String name2 = "b";
        String value2 = "2";
        final String query = name1 + "=" + value1 + "&" + name2 + "=" + value2;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(path + "?" + name1 + "=" + value1)
                .param(name2, value2);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(2, params.getSize());
        Assert.assertEquals(value1, params.get(name1).getValue());
        Assert.assertEquals(value2, params.get(name2).getValue());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testPathWithQueryAndParamValueEncoded() throws Exception
    {
        final String name1 = "a";
        final String value1 = "\u20AC";
        final String encodedValue1 = URLEncoder.encode(value1, "UTF-8");
        final String name2 = "b";
        final String value2 = "\u00A5";
        String encodedValue2 = URLEncoder.encode(value2, "UTF-8");
        final String query = name1 + "=" + encodedValue1 + "&" + name2 + "=" + encodedValue2;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
                Assert.assertEquals(value1, request.getParameter(name1));
                Assert.assertEquals(value2, request.getParameter(name2));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(path + "?" + name1 + "=" + encodedValue1)
                .param(name2, value2);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(2, params.getSize());
        Assert.assertEquals(value1, params.get(name1).getValue());
        Assert.assertEquals(value2, params.get(name2).getValue());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testNoParameterNameNoParameterValue() throws Exception
    {
        final String path = "/path";
        final String query = "="; // Bogus query
        String pathQuery = path + "?" + query;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(pathQuery);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(0, params.getSize());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testNoParameterNameWithParameterValue() throws Exception
    {
        final String path = "/path";
        final String query = "=1"; // Bogus query
        String pathQuery = path + "?" + query;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .path(pathQuery);

        Assert.assertEquals(path, request.getPath());
        Assert.assertEquals(query, request.getQuery());
        Assert.assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        Assert.assertEquals(0, params.getSize());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testCaseSensitiveParameterName() throws Exception
    {
        final String name1 = "a";
        final String name2 = "A";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(name1, request.getParameter(name1));
                Assert.assertEquals(name2, request.getParameter(name2));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/path?" + name1 + "=" + name1)
                .param(name2, name2)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRawQueryIsPreservedInURI() throws Exception
    {
        final String name = "a";
        final String rawValue = "Hello%20World";
        final String rawQuery = name + "=" + rawValue;
        final String value = "Hello World";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(rawQuery, request.getQueryString());
                Assert.assertEquals(value, request.getParameter(name));
            }
        });

        String uri = scheme + "://localhost:" + connector.getLocalPort() + "/path?" + rawQuery;
        Request request = client.newRequest(uri)
                .timeout(5, TimeUnit.SECONDS);
        Assert.assertEquals(rawQuery, request.getQuery());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRawQueryIsPreservedInPath() throws Exception
    {
        final String name = "a";
        final String rawValue = "Hello%20World";
        final String rawQuery = name + "=" + rawValue;
        final String value = "Hello World";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(rawQuery, request.getQueryString());
                Assert.assertEquals(value, request.getParameter(name));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/path?" + rawQuery)
                .timeout(5, TimeUnit.SECONDS);
        Assert.assertEquals(rawQuery, request.getQuery());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRawQueryIsPreservedWithParam() throws Exception
    {
        final String name1 = "a";
        final String name2 = "b";
        final String rawValue1 = "Hello%20World";
        final String rawQuery1 = name1 + "=" + rawValue1;
        final String value1 = "Hello World";
        final String value2 = "alfa omega";
        final String encodedQuery2 = name2 + "=" + URLEncoder.encode(value2, "UTF-8");
        final String query = rawQuery1 + "&" + encodedQuery2;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(query, request.getQueryString());
                Assert.assertEquals(value1, request.getParameter(name1));
                Assert.assertEquals(value2, request.getParameter(name2));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/path?" + rawQuery1)
                .param(name2, value2)
                .timeout(5, TimeUnit.SECONDS);
        Assert.assertEquals(query, request.getQuery());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testSchemeIsCaseInsensitive() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme.toUpperCase(Locale.ENGLISH))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testHostIsCaseInsensitive() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.newRequest("LOCALHOST", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testAsteriskFormTarget() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals("*", target);
                Assert.assertEquals("*", request.getPathInfo());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.OPTIONS)
                .scheme(scheme)
                .path("*")
                .timeout(5, TimeUnit.SECONDS);

        Assert.assertEquals("*", request.getPath());
        Assert.assertNull(request.getQuery());
        Fields params = request.getParams();
        Assert.assertEquals(0, params.getSize());
        Assert.assertNull(request.getURI());

        ContentResponse response = request.send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    private static class IDNRedirectServer implements Runnable
    {
        private final String location;
        private ServerSocket serverSocket;
        private int port;

        private IDNRedirectServer(final String location) throws IOException
        {
            this.location = location;
        }

        @Override
        public void run()
        {
            try
            {
                while (!Thread.currentThread().isInterrupted())
                {
                    try (Socket clientSocket = serverSocket.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                         PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), false))
                    {
                        // Ignore the request.
                        while (true)
                        {
                            String line = reader.readLine();
                            if (line == null || line.isEmpty())
                                break;
                        }

                        writer.append("HTTP/1.1 302 Found\r\n")
                                .append("Location: ").append(location).append("\r\n")
                                .append("Content-Length: 0\r\n")
                                .append("Connection: close\r\n")
                                .append("\r\n");
                        writer.flush();
                    }
                }
            }
            catch (SocketException x)
            {
                // ServerSocket has been closed.
            }
            catch (Throwable x)
            {
                x.printStackTrace();
            }
        }

        private void start() throws Exception
        {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("localhost", 0));
            port = serverSocket.getLocalPort();
            new Thread(this).start();
        }

        private void stop() throws Exception
        {
            serverSocket.close();
        }

        private int getLocalPort()
        {
            return port;
        }
    }
}
