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

package org.eclipse.jetty.websocket.server;

import java.net.URI;
import java.util.concurrent.Future;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.SecuredRedirectHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.helper.EchoServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class RedirectWebSocketClientTest
{
    public static Server server;
    public static URI serverWsUri;
    public static URI serverWssUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(0);
        httpConfig.setOutputBufferSize(32768);
        httpConfig.setRequestHeaderSize(8192);
        httpConfig.setResponseHeaderSize(8192);
        httpConfig.setSendServerVersion(true);
        httpConfig.setSendDateHeader(false);

        SslContextFactory sslContextFactory = newSslContextFactory();

        // SSL HTTP Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // SSL Connector
        ServerConnector sslConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(httpsConfig));
        sslConnector.setPort(0);
        server.addConnector(sslConnector);

        // Normal Connector
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.addServlet(EchoServlet.class, "/echo");

        HandlerList handlers = new HandlerList();

        handlers.addHandler(new SecuredRedirectHandler());
        handlers.addHandler(contextHandler);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();

        serverWsUri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        serverWssUri = URI.create("wss://localhost:" + sslConnector.getLocalPort() + "/");

        // adjust HttpConfiguration in connector
        HttpConnectionFactory connectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
        connectionFactory.getHttpConfiguration().setSecurePort(sslConnector.getLocalPort());
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private static SslContextFactory newSslContextFactory()
    {
        SslContextFactory ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        ssl.setKeyStorePassword("storepwd");
        ssl.setKeyManagerPassword("keypwd");
        return ssl;
    }

    @Test
    public void testRedirect() throws Exception
    {
        SslContextFactory ssl = new SslContextFactory.Client();
        ssl.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        ssl.setKeyStorePassword("storepwd");
        ssl.setKeyManagerPassword("keypwd");
        ssl.setTrustAll(false);
        ssl.setEndpointIdentificationAlgorithm(null);
        HttpClient httpClient = new HttpClient(ssl);

        WebSocketClient client = new WebSocketClient(httpClient);
        client.addBean(httpClient, true);
        client.start();

        try
        {
            URI wsUri = serverWsUri.resolve("/echo");

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            Future<Session> sessionFuture = client.connect(new EmptyWebSocket(), wsUri, request);
            Session session = sessionFuture.get();
            assertThat(session, is(notNullValue()));
        }
        finally
        {
            client.stop();
        }
    }

    @WebSocket
    public static class EmptyWebSocket
    {
    }
}
