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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpConfigurationAuthorityOverrideTest
{
    @Test
    public void testLocalAuthorityHttp10NoHostDump() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("foo.local.name", 80);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /dump HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[foo.local.name]"),
                containsString("ServerPort=[80]"),
                containsString("LocalAddr=[foo.local.name]"),
                containsString("LocalName=[foo.local.name]"),
                containsString("LocalPort=[80]"),
                containsString("RequestURL=[http://foo.local.name/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityHttp10NoHostRedirect() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("foo.local.name", 80);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /redirect HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.local.name/dump"));
        }
    }

    @Test
    public void testLocalAuthorityHttp10NotFound() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("foo.local.name", 777);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /bogus HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            // because of the custom error handler, we actually expect a redirect
            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.local.name:777/error"));
        }
    }

    @Test
    public void testLocalAuthorityHttp11EmptyHostDump() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("foo.local.name", 80);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[foo.local.name]"),
                containsString("ServerPort=[80]"),
                containsString("LocalAddr=[foo.local.name]"),
                containsString("LocalName=[foo.local.name]"),
                containsString("LocalPort=[80]"),
                containsString("RequestURL=[http://foo.local.name/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityHttp11EmptyHostRedirect() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("foo.local.name", 80);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connect: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.local.name/dump"));
        }
    }

    @Test
    public void testLocalAuthorityHttp11EmptyHostAbsUriDump() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("bar.local.name", 9999);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET mobile:///dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[bar.local.name]"),
                containsString("ServerPort=[9999]"),
                containsString("LocalAddr=[bar.local.name]"),
                containsString("LocalName=[bar.local.name]"),
                containsString("LocalPort=[9999]"),
                containsString("RequestURL=[mobile://bar.local.name:9999/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityHttp11ValidHostDump() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("zed.local.name", 9999);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[jetty.eclipse.org]"),
                containsString("ServerPort=[8888]"),
                containsString("LocalAddr=[zed.local.name]"),
                containsString("LocalName=[zed.local.name]"),
                containsString("LocalPort=[9999]"),
                containsString("RequestURL=[http://jetty.eclipse.org:8888/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityHttp11ValidHostRedirect() throws Exception
    {
        InetSocketAddress localAddress = InetSocketAddress.createUnresolved("zed.local.name", 9999);

        try (CloseableServer server = startServer(null, localAddress))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://jetty.eclipse.org:8888/dump"));
        }
    }

    @Test
    public void testServerAuthorityNoPortHttp11EmptyHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[foo.server.authority]"),
                containsString("ServerPort=[80]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://foo.server.authority/dump]")
            ));
        }
    }

    @Test
    public void testServerAuthorityNoPortHttp11EmptyHostRedirect() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connect: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority/dump"));
        }
    }

    @Test
    public void testServerAuthorityWithPortHttp11EmptyHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority:7777");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[foo.server.authority]"),
                containsString("ServerPort=[7777]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://foo.server.authority:7777/dump]")
            ));
        }
    }

    @Test
    public void testServerAuthorityWithPortHttp11EmptyHostRedirect() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority:7777");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connect: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority:7777/dump"));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp10NoHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[foo.server.authority]"),
                containsString("ServerPort=[80]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://foo.server.authority/dump]")
            ));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp10NoHostRedirect() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority/dump"));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp10NotFound() throws Exception
    {
        HostPort severUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(severUriAuthority, null))
        {
            String rawRequest = "GET /bogus HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            // because of the custom error handler, we actually expect a redirect
            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority/error"));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp10PathError() throws Exception
    {
        HostPort severUriAuthority = new HostPort("foo.server.authority");

        try (CloseableServer server = startServer(severUriAuthority, null))
        {
            String rawRequest = "GET /%00 HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp11ValidHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("zed.server.authority");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[jetty.eclipse.org]"),
                containsString("ServerPort=[8888]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://jetty.eclipse.org:8888/dump]")
            ));
        }
    }

    @Test
    public void testServerUriAuthorityNoPortHttp11ValidHostRedirect() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("zed.local.name");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://jetty.eclipse.org:8888/dump"));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp10NoHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("bar.server.authority:9999");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[bar.server.authority]"),
                containsString("ServerPort=[9999]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://bar.server.authority:9999/dump]")
            ));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp10NoHostRedirect() throws Exception
    {
        HostPort severUriAuthority = new HostPort("foo.server.authority:9999");

        try (CloseableServer server = startServer(severUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority:9999/dump"));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp10NotFound() throws Exception
    {
        HostPort severUriAuthority = new HostPort("foo.server.authority:7777");

        try (CloseableServer server = startServer(severUriAuthority, null))
        {
            String rawRequest = "GET /bogus HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            // because of the custom error handler, we actually expect a redirect
            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://foo.server.authority:7777/error"));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp11ValidHostDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("zed.server.authority:7777");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[jetty.eclipse.org]"),
                containsString("ServerPort=[8888]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://jetty.eclipse.org:8888/dump]")
            ));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp11EmptyHostAbsUriDump() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("zed.server.authority:7777");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET mobile:///dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[zed.server.authority]"),
                containsString("ServerPort=[7777]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[mobile://zed.server.authority:7777/dump]")
            ));
        }
    }

    @Test
    public void testServerUriAuthorityWithPortHttp11ValidHostRedirect() throws Exception
    {
        HostPort serverUriAuthority = new HostPort("zed.local.name:7777");

        try (CloseableServer server = startServer(serverUriAuthority, null))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: jetty.eclipse.org:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://jetty.eclipse.org:8888/dump"));
        }
    }

    @Test
    public void testUnsetAuthoritiesHttp11EmptyHostDump() throws Exception
    {
        try (CloseableServer server = startServer(null, null))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[" + server.getConnectorLocalName() + "]"),
                containsString("ServerPort=[" + server.getConnectorLocalPort() + "]"),
                // expect default locals
                containsString("LocalAddr=[" + server.getConnectorLocalAddr() + "]"),
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://" + server.getConnectorLocalName() + ":" + server.getConnectorLocalPort() + "/dump]")
            ));
        }
    }

    @Test
    public void testUnsetAuthoritiesHttp11EmptyHostRedirect() throws Exception
    {
        try (CloseableServer server = startServer(null, null))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://" + server.getConnectorLocalName() + ":" + server.getConnectorLocalPort() + "/dump"));
        }
    }

    private HttpTester.Response issueRequest(CloseableServer server, String rawRequest) throws Exception
    {
        try (Socket socket = new Socket("localhost", server.getConnectorLocalPort());
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream())
        {
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));
            assertNotNull(response, "response");
            return response;
        }
    }

    private CloseableServer startServer(HostPort serverUriAuthority, InetSocketAddress localAddress) throws Exception
    {
        Server server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        if (serverUriAuthority != null)
            httpConfiguration.setServerAuthority(serverUriAuthority);
        if (localAddress != null)
            httpConfiguration.setLocalAddress(localAddress);

        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
        connector.setPort(0);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(new RedirectHandler());
        handlers.addHandler(new DumpHandler());
        handlers.addHandler(new ErrorMsgHandler());
        server.setHandler(handlers);

        server.setErrorHandler(new RedirectErrorHandler());
        server.start();

        return new CloseableServer(server, connector);
    }

    private static class DumpHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (target.startsWith("/dump"))
            {
                baseRequest.setHandled(true);
                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");
                PrintWriter out = response.getWriter();
                out.printf("ServerName=[%s]%n", request.getServerName());
                out.printf("ServerPort=[%d]%n", request.getServerPort());
                out.printf("LocalName=[%s]%n", request.getLocalName());
                out.printf("LocalAddr=[%s]%n", request.getLocalAddr());
                out.printf("LocalPort=[%s]%n", request.getLocalPort());
                out.printf("RequestURL=[%s]%n", request.getRequestURL());
            }
        }
    }

    private static class RedirectHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (target.startsWith("/redirect"))
            {
                baseRequest.setHandled(true);
                response.sendRedirect("/dump");
            }
        }
    }

    private static class ErrorMsgHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (target.startsWith("/error"))
            {
                baseRequest.setHandled(true);
                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");
                response.getWriter().println("Generic Error Page.");
            }
        }
    }

    public static class RedirectErrorHandler extends ErrorHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.sendRedirect("/error");
        }
    }

    private static class CloseableServer implements AutoCloseable
    {
        private final Server server;
        private final ServerConnector connector;

        public CloseableServer(Server server, ServerConnector connector)
        {
            this.server = Objects.requireNonNull(server, "Server");
            this.connector = Objects.requireNonNull(connector, "Connector");
        }

        @Override
        public void close() throws Exception
        {
            LifeCycle.stop(this.server);
        }

        public String getConnectorLocalAddr()
        {
            return "127.0.0.1";
        }

        public String getConnectorLocalName()
        {
            return HostPort.normalizeHost(getConnectorLocalAddr());
        }

        public int getConnectorLocalPort()
        {
            return this.connector.getLocalPort();
        }
    }
}
