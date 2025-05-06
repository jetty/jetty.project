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

package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HTTP2RequestTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        client = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        client.start();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();

        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        Path keystore = MavenPaths.findTestResourceFile("keystore.p12");
        serverTLS.setKeyStorePath(keystore.toString());
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setCipherComparator(new HTTP2Cipher.CipherComparator());

        HttpConfiguration httpsConfig = new HttpConfiguration();
        SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
        secureRequestCustomizer.setSniRequired(false);
        secureRequestCustomizer.setSniHostCheck(false);
        httpsConfig.addCustomizer(secureRequestCustomizer);

        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLS, alpn.getProtocol());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);

        ServerConnector connector = new ServerConnector(server, ssl, alpn, h2, h1);
        server.addConnector(connector);

        server.setHandler(contextHandler);
        server.start();
    }

    @Test
    public void testNormalDump() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/demo");
        contextHandler.addServlet(DumpServlet.class, "/dump/*");
        startServer(contextHandler);

        URI uri = server.getURI().resolve("/demo/dump/");

        ContentResponse response = client.newRequest(uri.getHost(), uri.getPort())
            .scheme(HttpScheme.HTTPS.asString())
            .path(uri.getPath())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertThat(response.getContentAsString(), allOf(
            containsString("RequestURI: /demo/dump/\n"),
            containsString("QueryString: null\n")
        ));
    }

    @Test
    public void testBadPathDump() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/demo");
        contextHandler.addServlet(DumpServlet.class, "/dump/*");
        startServer(contextHandler);

        // The "%00" will trigger a IllegalArgumentException: Illegal character in path (on server)
        // This character is allowed in the java.net.URI case.
        URI uri = server.getURI().resolve("/demo/dump/%00");

        ContentResponse response = client.newRequest(uri)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertThat(response.getContentAsString(), allOf(
            containsString("RequestURI: /demo/dump/%00\n"),
            containsString("QueryString: null\n")
        ));
    }

    public static class DumpServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            PrintWriter out = resp.getWriter();
            out.printf("Method: %s\n", req.getMethod());
            out.printf("RequestURL: %s\n", req.getRequestURL());
            out.printf("RequestURI: %s\n", req.getRequestURI());
            out.printf("QueryString: %s\n", req.getQueryString());
        }
    }
}
