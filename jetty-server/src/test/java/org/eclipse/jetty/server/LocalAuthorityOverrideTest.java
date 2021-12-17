//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
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
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LocalAuthorityOverrideTest
{
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

    private static class CloseableServer implements AutoCloseable
    {
        private final Server server;
        private final ServerConnector connector;

        public CloseableServer(Server server, ServerConnector connector)
        {
            this.server = Objects.requireNonNull(server, "Server");
            this.connector = Objects.requireNonNull(connector, "Connector");
        }

        public String getConnectorLocalName()
        {
            return HostPort.normalizeHost(this.connector.getLocalName());
        }

        public int getConnectorLocalPort()
        {
            return this.connector.getLocalPort();
        }

        @Override
        public void close() throws Exception
        {
            LifeCycle.stop(this.server);
        }
    }

    private CloseableServer startServer() throws Exception
    {
        return startServer(null);
    }

    private CloseableServer startServer(HostPort localAuthority) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        if (localAuthority != null)
            connector.setLocalAuthority(localAuthority);
        connector.setPort(0);

        server.addConnector(connector);
        HandlerList handlers = new HandlerList();
        handlers.addHandler(new RedirectHandler());
        handlers.addHandler(new DumpHandler());
        server.setHandler(handlers);
        server.start();

        return new CloseableServer(server, connector);
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

    @Test
    public void testLocalAuthorityNoPortHttp10NoHostRedirect() throws Exception
    {
        HostPort localAuthority = new HostPort("FooLocalName");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /redirect HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://FooLocalName/dump"));
        }
    }

    @Test
    public void testLocalAuthorityNoPortHttp10NoHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("FooLocalName");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /dump HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[FooLocalName]"),
                containsString("ServerPort=[80]"),
                containsString("LocalName=[FooLocalName]"),
                containsString("LocalPort=[0]"),
                containsString("RequestURL=[http://FooLocalName/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityWithPortHttp10NoHostRedirect() throws Exception
    {
        HostPort localAuthority = new HostPort("BarLocalName:9999");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /redirect HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://BarLocalName:9999/dump"));
        }
    }

    @Test
    public void testLocalAuthorityWithPortHttp10NoHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("BarLocalName:9999");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /dump HTTP/1.0\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[BarLocalName]"),
                containsString("ServerPort=[9999]"),
                containsString("LocalName=[BarLocalName]"),
                containsString("LocalPort=[9999]"),
                containsString("RequestURL=[http://BarLocalName:9999/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityNoPortHttp11EmptyHostRedirect() throws Exception
    {
        HostPort localAuthority = new HostPort("FooLocalName");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connect: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://FooLocalName/dump"));
        }
    }

    @Test
    public void testLocalAuthorityNoPortHttp11EmptyHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("FooLocalName");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[FooLocalName]"),
                containsString("ServerPort=[80]"),
                containsString("LocalName=[FooLocalName]"),
                containsString("LocalPort=[0]"),
                containsString("RequestURL=[http://FooLocalName/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityWithPortHttp11EmptyHostRedirect() throws Exception
    {
        HostPort localAuthority = new HostPort("BarLocalName:9999");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /redirect HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            String location = response.get(HttpHeader.LOCATION);
            assertThat(location, is("http://BarLocalName:9999/dump"));
        }
    }

    @Test
    public void testLocalAuthorityWithPortHttp11EmptyHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("BarLocalName:9999");

        try (CloseableServer server = startServer(localAuthority))
        {
            String rawRequest = "GET /dump HTTP/1.1\r\n" +
                "Host: \r\n" +
                "Connection: close\r\n" +
                "\r\n";

            HttpTester.Response response = issueRequest(server, rawRequest);

            assertThat("response.status", response.getStatus(), is(200));
            String responseContent = response.getContent();
            assertThat("response content", responseContent, allOf(
                containsString("ServerName=[BarLocalName]"),
                containsString("ServerPort=[9999]"),
                containsString("LocalName=[BarLocalName]"),
                containsString("LocalPort=[9999]"),
                containsString("RequestURL=[http://BarLocalName:9999/dump]")
            ));
        }
    }

    @Test
    public void testUnsetLocalAuthorityHttp11EmptyHostRedirect() throws Exception
    {
        try (CloseableServer server = startServer())
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

    @Test
    public void testUnsetLocalAuthorityHttp11EmptyHostDump() throws Exception
    {
        try (CloseableServer server = startServer())
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
                containsString("LocalName=[" + server.getConnectorLocalName() + "]"),
                containsString("LocalPort=[" + server.getConnectorLocalPort() + "]"),
                containsString("RequestURL=[http://" + server.getConnectorLocalName() + ":" + server.getConnectorLocalPort() + "/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityNoPortHttp11ValidHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("ZedLocalName");

        try (CloseableServer server = startServer(localAuthority))
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
                containsString("LocalName=[ZedLocalName]"),
                containsString("LocalPort=[0]"),
                containsString("RequestURL=[http://jetty.eclipse.org:8888/dump]")
            ));
        }
    }

    @Test
    public void testLocalAuthorityWithPortHttp11ValidHostDump() throws Exception
    {
        HostPort localAuthority = new HostPort("ZedLocalName:9999");

        try (CloseableServer server = startServer(localAuthority))
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
                containsString("LocalName=[ZedLocalName]"),
                containsString("LocalPort=[9999]"),
                containsString("RequestURL=[http://jetty.eclipse.org:8888/dump]")
            ));
        }
    }
}
