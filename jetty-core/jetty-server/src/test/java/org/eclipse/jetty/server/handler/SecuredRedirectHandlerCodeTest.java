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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SecuredRedirectHandlerCodeTest
{
    private Server server;
    private HostnameVerifier origVerifier;
    private SSLSocketFactory origSsf;
    private URI serverHttpUri;
    private URI serverHttpsUri;

    @Test
    public void testConstructorRedirectRangeValid()
    {
        assertDoesNotThrow(() -> new SecuredRedirectHandler(300));
        assertDoesNotThrow(() -> new SecuredRedirectHandler(399));
    }

    @Test
    public void testConstructorRedirectRangeInvalid()
    {
        assertThrows(IllegalArgumentException.class, () -> new SecuredRedirectHandler(299));
        assertThrows(IllegalArgumentException.class, () -> new SecuredRedirectHandler(400));
    }

    @Test
    public void testRedirectUnsecuredRootMovedTemporarily() throws Exception
    {
        try
        {
            startServer(HttpServletResponse.SC_MOVED_TEMPORARILY);
            URL url = serverHttpUri.resolve("/").toURL();
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setAllowUserInteraction(false);
            assertThat("response code", connection.getResponseCode(), is(302));
            assertThat("location header", connection.getHeaderField("Location"), is(serverHttpsUri.resolve("/").toASCIIString()));
            connection.disconnect();
        }
        finally
        {
            stopServer();
        }
    }

    @Test
    public void testRedirectUnsecuredRootMovedPermanently() throws Exception
    {
        try
        {
            startServer(HttpServletResponse.SC_MOVED_PERMANENTLY);
            URL url = serverHttpUri.resolve("/").toURL();
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setAllowUserInteraction(false);
            assertThat("response code", connection.getResponseCode(), is(301));
            assertThat("location header", connection.getHeaderField("Location"), is(serverHttpsUri.resolve("/").toASCIIString()));
            connection.disconnect();
        }
        finally
        {
            stopServer();
        }
    }

    private void startServer(int redirectCode) throws Exception
    {
        // Setup SSL
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();

        int port = 32080;
        int securePort = 32443;

        // Setup HTTP Configuration
        HttpConfiguration httpConf = new HttpConfiguration();
        httpConf.setSecurePort(securePort);
        httpConf.setSecureScheme("https");

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConf));
        httpConnector.setName("unsecured");
        httpConnector.setPort(port);

        // Setup HTTPS Configuration
        HttpConfiguration httpsConf = new HttpConfiguration(httpConf);
        httpsConf.addCustomizer(new SecureRequestCustomizer());

        ServerConnector httpsConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConf));
        httpsConnector.setName("secured");
        httpsConnector.setPort(securePort);

        // Add connectors
        server.setConnectors(new Connector[]{httpConnector, httpsConnector});

        // Wire up context for unsecure handling to only
        // the named 'unsecured' connector
        ContextHandler redirectHandler = new ContextHandler();
        redirectHandler.setContextPath("/");
        redirectHandler.setHandler(new SecuredRedirectHandler(redirectCode));
        redirectHandler.setVirtualHosts(new String[]{"@unsecured"});

        // Establish all handlers that have a context
        ContextHandlerCollection contextHandlers = new ContextHandlerCollection();
        contextHandlers.setHandlers(new Handler[]{redirectHandler});

        // Create server level handler tree
        server.setHandler(new HandlerList(contextHandlers, new DefaultHandler()));

        server.start();

        // calculate serverUri
        String host = httpConnector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        serverHttpUri = new URI(String.format("http://%s:%d/", host, httpConnector.getLocalPort()));
        serverHttpsUri = new URI(String.format("https://%s:%d/", host, httpsConnector.getLocalPort()));

        origVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        origSsf = HttpsURLConnection.getDefaultSSLSocketFactory();

        HttpsURLConnection.setDefaultHostnameVerifier(new AllowAllVerifier());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContextFactory.getSslContext().getSocketFactory());
    }

    private void stopServer() throws Exception
    {
        HttpsURLConnection.setDefaultSSLSocketFactory(origSsf);
        HttpsURLConnection.setDefaultHostnameVerifier(origVerifier);

        server.stop();
        server.join();
    }
}
