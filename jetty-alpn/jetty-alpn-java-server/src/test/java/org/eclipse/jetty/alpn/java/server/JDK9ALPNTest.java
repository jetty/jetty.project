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

package org.eclipse.jetty.alpn.java.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class JDK9ALPNTest
{
    private Server server;
    private ServerConnector connector;

    public void startServer(Handler handler) throws Exception
    {
        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        connector = new ServerConnector(server, newSslContextFactory(), alpn, h1, h2);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    private SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setIncludeProtocols("TLSv1.2");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        return sslContextFactory;
    }

    @Test
    public void testClientNotSupportingALPNServerSpeaksDefaultProtocol() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
            }
        });

        SslContextFactory sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
        {
            client.setUseClientMode(true);
            client.setSoTimeout(5000);
            client.startHandshake();

            OutputStream output = client.getOutputStream();
            output.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertThat(line, containsString(" 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }

    @Test
    public void testClientSupportingALPNServerSpeaksNegotiatedProtocol() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
            }
        });

        SslContextFactory sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
        {
            client.setUseClientMode(true);
            SSLParameters sslParameters = client.getSSLParameters();
            sslParameters.setApplicationProtocols(new String[]{"unknown/1.0", "http/1.1"});
            client.setSSLParameters(sslParameters);
            client.setSoTimeout(5000);
            client.startHandshake();

            OutputStream output = client.getOutputStream();
            output.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertThat(line, containsString(" 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }

    @Test
    public void testClientSupportingALPNCannotNegotiateProtocol() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                jettyRequest.setHandled(true);
            }
        });

        SslContextFactory sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.start();
        String host = "localhost";
        int port = connector.getLocalPort();
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress(host, port)))
        {
            client.socket().setSoTimeout(5000);

            SSLEngine sslEngine = sslContextFactory.newSSLEngine(host, port);
            sslEngine.setUseClientMode(true);
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            sslParameters.setApplicationProtocols(new String[]{"unknown/1.0"});
            sslEngine.setSSLParameters(sslParameters);
            sslEngine.beginHandshake();
            assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

            ByteBuffer sslBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());

            SSLEngineResult result = sslEngine.wrap(BufferUtil.EMPTY_BUFFER, sslBuffer);
            assertSame(SSLEngineResult.Status.OK, result.getStatus());

            sslBuffer.flip();
            client.write(sslBuffer);

            assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());

            sslBuffer.clear();
            int read = client.read(sslBuffer);
            assertThat(read, greaterThan(0));

            sslBuffer.flip();
            // TLS frame layout: record_type, major_version, minor_version, hi_length, lo_length
            int recordTypeAlert = 21;
            assertEquals(recordTypeAlert, sslBuffer.get(0) & 0xFF);
            // Alert record layout: alert_level, alert_code
            int alertLevelFatal = 2;
            assertEquals(alertLevelFatal, sslBuffer.get(5) & 0xFF);
            int alertCodeNoApplicationProtocol = 120;
            assertEquals(alertCodeNoApplicationProtocol, sslBuffer.get(6) & 0xFF);
        }
    }
}
