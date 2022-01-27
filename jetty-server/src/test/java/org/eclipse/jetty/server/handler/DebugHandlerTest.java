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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class DebugHandlerTest
{
    public static final HostnameVerifier __hostnameverifier = (hostname, session) -> true;

    private SSLContext sslContext;
    private Server server;
    private URI serverURI;
    private URI secureServerURI;

    private DebugHandler debugHandler;
    private ByteArrayOutputStream capturedLog;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector httpConnector = new ServerConnector(server);
        httpConnector.setPort(0);
        server.addConnector(httpConnector);

        File keystorePath = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        ByteBufferPool pool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());
        ServerConnector sslConnector = new ServerConnector(server, null, null, pool, 1, 1,
            AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));

        server.addConnector(sslConnector);

        debugHandler = new DebugHandler();
        capturedLog = new ByteArrayOutputStream();
        debugHandler.setOutputStream(capturedLog);
        debugHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
            }
        });
        server.setHandler(debugHandler);
        server.start();

        String host = httpConnector.getHost();
        if (host == null)
            host = "localhost";

        serverURI = URI.create(String.format("http://%s:%d/", host, httpConnector.getLocalPort()));
        secureServerURI = URI.create(String.format("https://%s:%d/", host, sslConnector.getLocalPort()));

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = sslContextFactory.getKeyStoreResource().getInputStream())
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        try
        {
            HttpsURLConnection.setDefaultHostnameVerifier(__hostnameverifier);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testThreadName() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)serverURI.resolve("/foo/bar?a=b").toURL().openConnection();
        assertThat("Response Code", http.getResponseCode(), is(200));

        String log = capturedLog.toString(StandardCharsets.UTF_8.name());
        String expectedThreadName = ":/foo/bar?a=b";
        assertThat("ThreadName", log, containsString(expectedThreadName));
        // Look for bad/mangled/duplicated schemes
        assertThat("ThreadName", log, not(containsString("http:" + expectedThreadName)));
        assertThat("ThreadName", log, not(containsString("https:" + expectedThreadName)));
    }

    @Test
    public void testSecureThreadName() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)secureServerURI.resolve("/foo/bar?a=b").toURL().openConnection();
        assertThat("Response Code", http.getResponseCode(), is(200));

        String log = capturedLog.toString(StandardCharsets.UTF_8.name());
        String expectedThreadName = ":/foo/bar?a=b";
        assertThat("ThreadName", log, containsString(expectedThreadName));
        // Look for bad/mangled/duplicated schemes
        assertThat("ThreadName", log, not(containsString("http:" + expectedThreadName)));
        assertThat("ThreadName", log, not(containsString("https:" + expectedThreadName)));
    }
}
