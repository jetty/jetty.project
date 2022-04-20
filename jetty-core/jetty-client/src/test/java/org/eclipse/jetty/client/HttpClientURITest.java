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

package org.eclipse.jetty.client;

import java.io.BufferedReader;
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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientURITest extends AbstractHttpClientServerTest
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIPv6Host(Scenario scenario) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        start(scenario, new EmptyServerHandler());

        String hostAddress = "::1";
        String host = "[" + hostAddress + "]";
        // Explicitly use a non-bracketed IPv6 host.
        Request request = client.newRequest(hostAddress, connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS);

        assertEquals(host, request.getHost());
        StringBuilder uri = new StringBuilder();
        URIUtil.appendSchemeHostPort(uri, scenario.getScheme(), host, connector.getLocalPort());
        assertEquals(uri.toString(), request.getURI().toString());

        assertEquals(HttpStatus.OK_200, request.send().getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIDNHost(Scenario scenario) throws Exception
    {
        startClient(scenario);
        assertThrows(IllegalArgumentException.class, () ->
        {
            client.newRequest(scenario.getScheme() + "://пример.рф"); // example.com-like host in IDN domain
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIDNRedirect(Scenario scenario) throws Exception
    {
        // Internationalized Domain Name.
        // String exampleHost = scheme + "://пример.рф";
        String exampleHost = scenario.getScheme() + "://\uD0BF\uD180\uD0B8\uD0BC\uD0B5\uD180.\uD180\uD184";
        String incorrectlyDecoded = new String(exampleHost.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        // Simple server that only parses clear-text HTTP/1.1.
        IDNRedirectServer server = new IDNRedirectServer(exampleHost);
        server.start();

        try
        {
            startClient(scenario);

            ContentResponse response = client.newRequest("localhost", server.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .send();

            HttpField location = response.getHeaders().getField(HttpHeader.LOCATION);
            assertEquals(incorrectlyDecoded, location.getValue());

            ExecutionException x = assertThrows(ExecutionException.class, () ->
            {
                client.newRequest("localhost", server.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .send();
            });
            assertThat(x.getCause(), instanceOf(IllegalArgumentException.class));
        }
        finally
        {
            server.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHostPort(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("domain.com", 80)
            .scheme(scenario.getScheme())
            .host("localhost")
            .port(connector.getLocalPort())
            .timeout(1, TimeUnit.SECONDS);

        assertEquals("localhost", request.getHost());
        assertEquals(connector.getLocalPort(), request.getPort());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPath(Scenario scenario) throws Exception
    {
        final String path = "/path";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(path);

        assertEquals(path, request.getPath());
        assertNull(request.getQuery());
        Fields params = request.getParams();
        assertEquals(0, params.getSize());
        assertTrue(request.getURI().toString().endsWith(path));

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPathWithQuery(Scenario scenario) throws Exception
    {
        String name = "a";
        String value = "1";
        final String query = name + "=" + value;
        final String path = "/path";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
            }
        });

        String pathQuery = path + "?" + query;
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(pathQuery);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(1, params.getSize());
        assertEquals(value, params.get(name).getValue());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPathWithParam(Scenario scenario) throws Exception
    {
        String name = "a";
        String value = "1";
        final String query = name + "=" + value;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(path)
            .param(name, value);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(1, params.getSize());
        assertEquals(value, params.get(name).getValue());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPathWithQueryAndParam(Scenario scenario) throws Exception
    {
        String name1 = "a";
        String value1 = "1";
        String name2 = "b";
        String value2 = "2";
        final String query = name1 + "=" + value1 + "&" + name2 + "=" + value2;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(path + "?" + name1 + "=" + value1)
            .param(name2, value2);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(2, params.getSize());
        assertEquals(value1, params.get(name1).getValue());
        assertEquals(value2, params.get(name2).getValue());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testPathWithQueryAndParamValueEncoded(Scenario scenario) throws Exception
    {
        final String name1 = "a";
        final String value1 = "\u20AC";
        final String encodedValue1 = URLEncoder.encode(value1, StandardCharsets.UTF_8);
        final String name2 = "b";
        final String value2 = "\u00A5";
        String encodedValue2 = URLEncoder.encode(value2, StandardCharsets.UTF_8);
        final String query = name1 + "=" + encodedValue1 + "&" + name2 + "=" + encodedValue2;
        final String path = "/path";
        String pathQuery = path + "?" + query;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                assertEquals(value1, fields.getValue(name1));
                assertEquals(value2, fields.getValue(name2));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(path + "?" + name1 + "=" + encodedValue1)
            .param(name2, value2);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(2, params.getSize());
        assertEquals(value1, params.get(name1).getValue());
        assertEquals(value2, params.get(name2).getValue());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testNoParameterNameNoParameterValue(Scenario scenario) throws Exception
    {
        final String path = "/path";
        final String query = "="; // Bogus query
        String pathQuery = path + "?" + query;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(pathQuery);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(0, params.getSize());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testNoParameterNameWithParameterValue(Scenario scenario) throws Exception
    {
        final String path = "/path";
        final String query = "=1"; // Bogus query
        String pathQuery = path + "?" + query;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(path, request.getPathInContext());
                assertEquals(query, request.getHttpURI().getQuery());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .path(pathQuery);

        assertEquals(path, request.getPath());
        assertEquals(query, request.getQuery());
        assertTrue(request.getURI().toString().endsWith(pathQuery));
        Fields params = request.getParams();
        assertEquals(0, params.getSize());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCaseSensitiveParameterName(Scenario scenario) throws Exception
    {
        final String name1 = "a";
        final String name2 = "A";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                assertEquals(name1, fields.getValue(name1));
                assertEquals(name2, fields.getValue(name2));
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/path?" + name1 + "=" + name1)
            .param(name2, name2)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRawQueryIsPreservedInURI(Scenario scenario) throws Exception
    {
        final String name = "a";
        final String rawValue = "Hello%20World";
        final String rawQuery = name + "=" + rawValue;
        final String value = "Hello World";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(rawQuery, request.getHttpURI().getQuery());
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                assertEquals(value, fields.getValue(name));
            }
        });

        String uri = scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/path?" + rawQuery;
        Request request = client.newRequest(uri)
            .timeout(5, TimeUnit.SECONDS);
        assertEquals(rawQuery, request.getQuery());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRawQueryIsPreservedInPath(Scenario scenario) throws Exception
    {
        final String name = "a";
        final String rawValue = "Hello%20World";
        final String rawQuery = name + "=" + rawValue;
        final String value = "Hello World";
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(rawQuery, request.getHttpURI().getQuery());
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                assertEquals(value, fields.getValue(name));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/path?" + rawQuery)
            .timeout(5, TimeUnit.SECONDS);
        assertEquals(rawQuery, request.getQuery());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRawQueryIsPreservedWithParam(Scenario scenario) throws Exception
    {
        final String name1 = "a";
        final String name2 = "b";
        final String rawValue1 = "Hello%20World";
        final String rawQuery1 = name1 + "=" + rawValue1;
        final String value1 = "Hello World";
        final String value2 = "alfa omega";
        final String encodedQuery2 = name2 + "=" + URLEncoder.encode(value2, StandardCharsets.UTF_8);
        final String query = rawQuery1 + "&" + encodedQuery2;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals(query, request.getHttpURI().getQuery());
                Fields fields = org.eclipse.jetty.server.Request.extractQueryParameters(request);
                assertEquals(value1, fields.getValue(name1));
                assertEquals(value2, fields.getValue(name2));
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/path?" + rawQuery1)
            .param(name2, value2)
            .timeout(5, TimeUnit.SECONDS);
        assertEquals(query, request.getQuery());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSchemeIsCaseInsensitive(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme().toUpperCase(Locale.ENGLISH))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testHostIsCaseInsensitive(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        ContentResponse response = client.newRequest("LOCALHOST", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAsteriskFormTarget(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                assertEquals("*", request.getHttpURI().getPath());
                assertEquals("*", request.getPathInContext());
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.OPTIONS)
            .scheme(scenario.getScheme())
            .path("*")
            .timeout(5, TimeUnit.SECONDS);

        assertEquals("*", request.getPath());
        assertNull(request.getQuery());
        Fields params = request.getParams();
        assertEquals(0, params.getSize());
        assertNull(request.getURI());

        ContentResponse response = request.send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    private static class IDNRedirectServer implements Runnable
    {
        private final String location;
        private ServerSocket serverSocket;
        private int port;

        private IDNRedirectServer(final String location)
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
                            if (StringUtil.isEmpty(line))
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
