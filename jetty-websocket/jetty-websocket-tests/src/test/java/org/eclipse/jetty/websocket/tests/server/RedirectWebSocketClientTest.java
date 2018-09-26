//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.SecuredRedirectHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.TestWebSocketNegotiator;
import org.eclipse.jetty.websocket.core.TestWebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class RedirectWebSocketClientTest
{
    public Server server;
    public URI serverWsUri;
    public URI serverWssUri;
    public TestFrameHandler frameHandler;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(0);
        http_config.setOutputBufferSize(32768);
        http_config.setRequestHeaderSize(8192);
        http_config.setResponseHeaderSize(8192);
        http_config.setSendServerVersion(true);
        http_config.setSendDateHeader(false);

        SslContextFactory sslContextFactory = newSslContextFactory();

        // SSL HTTP Configuration
        HttpConfiguration https_config = new HttpConfiguration(http_config);
        https_config.addCustomizer(new SecureRequestCustomizer());

        // SSL Connector
        ServerConnector sslConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(https_config));
        sslConnector.setPort(0);
        server.addConnector(sslConnector);

        // Normal Connector
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler contextHandler = new ContextHandler("/");
        frameHandler = new TestFrameHandler();
        WebSocketNegotiator negotiator =  new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(), connector.getByteBufferPool(), frameHandler);
        WebSocketUpgradeHandler upgradeHandler = new TestWebSocketUpgradeHandler(negotiator);
        contextHandler.setHandler(upgradeHandler);

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

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    private static SslContextFactory newSslContextFactory()
    {
        SslContextFactory ssl = new SslContextFactory();
        ssl.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        ssl.setKeyStorePassword("storepwd");
        ssl.setKeyManagerPassword("keypwd");
        return ssl;
    }

    @Test
    public void testRedirect() throws Exception
    {
        SslContextFactory ssl = newSslContextFactory();
        ssl.setTrustAll(false);
        ssl.setEndpointIdentificationAlgorithm(null);
        HttpClient httpClient = new HttpClient(ssl);

        WebSocketClient client = new WebSocketClient(httpClient);
        client.addBean(httpClient, true);
        client.start();


        try
        {
            URI wsUri = serverWsUri.resolve("/test");

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("test");
            Future<Session> sessionFuture = client.connect(new EmptyWebSocket(), wsUri, request);
            Session session = sessionFuture.get();
            assertThat(session, is(notNullValue()));
        }
        finally
        {
            frameHandler.getCoreSession().close(CloseStatus.NORMAL, "",  Callback.NOOP);
            assertTrue(frameHandler.closed.await(5, TimeUnit.SECONDS));
        }

    }

    @WebSocket
    public static class EmptyWebSocket
    {
    }
}