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

package org.eclipse.jetty.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class DebugHandlerTest
{
    public static final String INBOUND_STARTS_WITH = ">> r=";
    public static final String OUTBOUND_STARTS_WITH = "<< r=";
    public static final HostnameVerifier __hostnameverifier = (hostname, session) -> true;

    private SSLContext sslContext;
    private Server server;
    private ArrayByteBufferPool.Tracking httpTrackingBufferPool;
    private ArrayByteBufferPool.Tracking sslTrackingBufferPool;
    private URI serverURI;
    private URI secureServerURI;

    private DebugHandler debugHandler;
    private ByteArrayOutputStream capturedLog;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        httpTrackingBufferPool = new ArrayByteBufferPool.Tracking();
        ServerConnector httpConnector = new ServerConnector(server, null, null, httpTrackingBufferPool, 1, 1, new HttpConnectionFactory());
        httpConnector.setPort(0);
        server.addConnector(httpConnector);

        Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toAbsolutePath().toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslTrackingBufferPool = new ArrayByteBufferPool.Tracking();
        ServerConnector sslConnector = new ServerConnector(server, null, null, sslTrackingBufferPool, 1, 1,
            AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));

        server.addConnector(sslConnector);

        debugHandler = new DebugHandler();
        capturedLog = new ByteArrayOutputStream();
        debugHandler.setOutputStream(capturedLog);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });
        server.insertHandler(debugHandler);

        server.start();

        String host = httpConnector.getHost();
        if (host == null)
            host = "localhost";

        serverURI = URI.create(String.format("http://%s:%d/", host, httpConnector.getLocalPort()));
        secureServerURI = URI.create(String.format("https://%s:%d/", host, sslConnector.getLocalPort()));

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = IOResources.asInputStream(sslContextFactory.getKeyStoreResource()))
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
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server HTTP Leaks: " + httpTrackingBufferPool.dumpLeaks(), httpTrackingBufferPool.getLeaks().size(), is(0)));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server SSL Leaks: " + sslTrackingBufferPool.dumpLeaks(), sslTrackingBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testDebugInboundOutboundMessages() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)serverURI.resolve("/foo/bar?a=b").toURL().openConnection();
        assertThat("Response Code", http.getResponseCode(), is(200));

        String log = capturedLog.toString(StandardCharsets.UTF_8.name());

        assertThat("Inbound", log, containsString(INBOUND_STARTS_WITH));
        assertThat("Outbound", log, containsString(OUTBOUND_STARTS_WITH));
    }

    @Test
    public void testSecureInboundOutboundMessages() throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)secureServerURI.resolve("/foo/bar?a=b").toURL().openConnection();
        assertThat("Response Code", http.getResponseCode(), is(200));

        String log = capturedLog.toString(StandardCharsets.UTF_8.name());
        System.err.println("****" + log + "******");

        assertThat("Inbound", log, containsString(INBOUND_STARTS_WITH));
        assertThat("Outbound", log, containsString(OUTBOUND_STARTS_WITH));
    }
}
