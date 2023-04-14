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

package org.eclipse.jetty.ee9.security;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.AbstractHandler;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class ClientCertAuthenticatorTest
{
    private static final String MESSAGE = "Yep CLIENT-CERT works";

    private Server server;
    private URI serverHttpsUri;
    private URI serverHttpUri;
    private HostnameVerifier origVerifier;
    private SSLSocketFactory origSsf;

    @BeforeEach
    public void setup() throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        origSsf = HttpsURLConnection.getDefaultSSLSocketFactory();
        origVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

        server = new Server();
        ContextHandler context = new ContextHandler();
        server.setHandler(context);

        int port = freePort();
        int securePort = freePort();
        SslContextFactory.Server sslContextFactory = createServerSslContextFactory("cacerts.jks", "changeit");
        // Setup HTTP Configuration
        HttpConfiguration httpConf = new HttpConfiguration();
        httpConf.setSecurePort(securePort);
        httpConf.setSecureScheme("https");

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConf));
        httpConnector.setName("unsecured");
        httpConnector.setPort(port);

        // Setup HTTPS Configuration
        HttpConfiguration httpsConf = new HttpConfiguration(httpConf);
        SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
        secureRequestCustomizer.setSniRequired(false);
        secureRequestCustomizer.setSniHostCheck(false);
        httpsConf.addCustomizer(secureRequestCustomizer);

        ServerConnector httpsConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConf));
        httpsConnector.setName("secured");
        httpsConnector.setPort(securePort);

        server.setConnectors(new Connector[]{httpConnector, httpsConnector});

        ConstraintSecurityHandler constraintSecurityHandler = new ConstraintSecurityHandler();
        constraintSecurityHandler.setAuthMethod(Authenticator.CERT_AUTH2);
        ConstraintMapping constraintMapping = new ConstraintMapping();
        ServletConstraint constraint = new ServletConstraint();
        constraint.setName(Authenticator.CERT_AUTH2);
        constraint.setRoles(new String[]{"Administrator"});
        constraint.setAuthenticate(true);
        constraintMapping.setConstraint(constraint);
        constraintMapping.setMethod("GET");
        constraintMapping.setPathSpec("/");
        constraintSecurityHandler.addConstraintMapping(constraintMapping);

        HashLoginService loginService = new HashLoginService();
        constraintSecurityHandler.setLoginService(loginService);

        ResourceFactory resourceFactory = ResourceFactory.of(constraintSecurityHandler);
        loginService.setConfig(resourceFactory.newResource("target/test-classes/realm.properties"));

        constraintSecurityHandler.setHandler(new FooHandler());
        context.setHandler(constraintSecurityHandler);
        server.addBean(sslContextFactory);
        server.start();

        String host = httpConnector.getHost();
        if (host == null)
            host = "localhost";
        serverHttpsUri = new URI(String.format("https://%s:%d/", host, httpsConnector.getLocalPort()));
        serverHttpUri = new URI(String.format("http://%s:%d/", host, httpConnector.getLocalPort()));
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (origVerifier != null)
            HttpsURLConnection.setDefaultHostnameVerifier(origVerifier);
        if (origSsf != null)
            HttpsURLConnection.setDefaultSSLSocketFactory(origSsf);
        server.stop();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    private SslContextFactory.Server createServerSslContextFactory(String trustStorePath, String trustStorePassword)
    {
        SslContextFactory.Server cf = new SslContextFactory.Server();
        cf.setNeedClientAuth(true);
        cf.setTrustStorePassword(trustStorePassword);
        cf.setTrustStoreResource(ResourceFactory.root().newResource(MavenPaths.findTestResourceFile(trustStorePath)));
        cf.setKeyStoreResource(ResourceFactory.root().newResource(MavenPaths.findTestResourceFile("clientcert.jks")));
        cf.setKeyStorePassword("changeit");
        cf.setSniRequired(false);
        cf.setWantClientAuth(true);
        return cf;
    }

    @Test
    public void testAuthenticationWithClientCertificateSucceeds() throws Exception
    {
        HttpsURLConnection.setDefaultHostnameVerifier((s, sslSession) -> true);
        SslContextFactory.Server cf = createServerSslContextFactory("cacerts.jks", "changeit");
        cf.start();
        HttpsURLConnection.setDefaultSSLSocketFactory(cf.getSslContext().getSocketFactory());
        URL url = serverHttpsUri.resolve("/").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertThat("response code", connection.getResponseCode(), is(200));
        String response = IO.toString(connection.getInputStream());
        assertThat("response message", response, containsString(MESSAGE));
    }

    @Test
    public void testAuthenticationWithoutClientCertificateFails() throws Exception
    {
        URL url = serverHttpUri.resolve("/").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        assertThat("response code", connection.getResponseCode(), is(403));
    }

    private int freePort() throws IOException
    {
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", 0));
            return server.getLocalPort();
        }
    }

    private static class FooHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/plain; charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(MESSAGE);
        }
    }
}
