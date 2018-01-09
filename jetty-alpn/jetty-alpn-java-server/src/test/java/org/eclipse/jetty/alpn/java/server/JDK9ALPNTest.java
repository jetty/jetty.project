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

package org.eclipse.jetty.alpn.java.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.servlet.ServletException;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

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

    private SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
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
        startServer(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        SslContextFactory sslContextFactory = new SslContextFactory(true);
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
        {
            client.setUseClientMode(true);
            client.setSoTimeout(5000);
            client.startHandshake();

            OutputStream output = client.getOutputStream();
            output.write(("" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            Assert.assertTrue(line.contains(" 200 "));
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
        startServer(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        SslContextFactory sslContextFactory = new SslContextFactory(true);
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
            output.write(("" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            Assert.assertTrue(line.contains(" 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }

    }
}
