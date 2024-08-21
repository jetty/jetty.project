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

package org.eclipse.jetty.server.ssl;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SocketCustomizationListener;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SslConnectionFactoryTest
{
    private Server _server;
    private ServerConnector _connector;
    private int _port;

    @BeforeEach
    public void before() throws Exception
    {
        Path keystoreFile = MavenPaths.findTestResourceFile("keystore.p12");
        _server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(8443);
        httpConfig.setOutputBufferSize(32768);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystoreFile.toString());
        sslContextFactory.setKeyStorePassword("storepwd");

        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
        sslConnectionFactory.setEnsureSecureRequestCustomizer(true);
        ServerConnector https = _connector = new ServerConnector(_server,
            sslConnectionFactory,
            new HttpConnectionFactory());
        https.setPort(0);
        https.setIdleTimeout(30000);

        _server.addConnector(https);

        _server.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.write(true, BufferUtil.toBuffer("url=" + request.getHttpURI() + "\nhost=" + Request.getServerName(request)), callback);
                return true;
            }
        });

        _server.start();
        _port = https.getLocalPort();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        _server = null;
    }

    @Test
    public void testConnect() throws Exception
    {
        HttpTester.Response response = getResponse("127.0.0.1", null);
        assertThat(response.getContent(), containsString("host=127.0.0.1"));
    }

    @Test
    public void testSNIConnect() throws Exception
    {
        HttpTester.Response response = getResponse("localhost", "localhost", "localhost");
        assertThat(response.getContent(), containsString("host=localhost"));
    }

    @Test
    public void testBadHandshake() throws Exception
    {
        try (Socket socket = new Socket("127.0.0.1", _port);
             OutputStream out = socket.getOutputStream())
        {
            out.write("Rubbish".getBytes());
            out.flush();

            socket.setSoTimeout(1000);
            // Expect TLS message type == 21: Alert
            assertThat(socket.getInputStream().read(), Matchers.equalTo(21));
        }
    }

    @Test
    public void testSocketCustomization() throws Exception
    {
        final Queue<String> history = new LinkedBlockingQueue<>();

        _connector.addBean(new SocketCustomizationListener()
        {
            @Override
            protected void customize(Socket socket, Class<? extends Connection> connection, boolean ssl)
            {
                history.add("customize connector " + connection + "," + ssl);
            }
        });

        _connector.getBean(SslConnectionFactory.class).addBean(new SocketCustomizationListener()
        {
            @Override
            protected void customize(Socket socket, Class<? extends Connection> connection, boolean ssl)
            {
                history.add("customize ssl " + connection + "," + ssl);
            }
        });

        _connector.getBean(HttpConnectionFactory.class).addBean(new SocketCustomizationListener()
        {
            @Override
            protected void customize(Socket socket, Class<? extends Connection> connection, boolean ssl)
            {
                history.add("customize http " + connection + "," + ssl);
            }
        });

        HttpTester.Response response = getResponse("127.0.0.1", null);
        assertThat(response.getContent(), containsString("host=127.0.0.1"));

        assertEquals("customize connector class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize ssl class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize connector class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        assertEquals("customize http class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        assertEquals(0, history.size());
    }

    @Test
    public void testServerWithoutHttpConnectionFactory() throws Exception
    {
        _server.stop();
        assertNotNull(_connector.removeConnectionFactory(HttpVersion.HTTP_1_1.asString()));
        assertThrows(IllegalStateException.class, () -> _server.start());
    }

    private HttpTester.Response getResponse(String host, String cn) throws Exception
    {
        HttpTester.Response response = getResponse(host, host, cn);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("url=https://%s:%d/ctx/path".formatted(host, _port)));
        return response;
    }

    private HttpTester.Response getResponse(String sniHost, String reqHost, String cn) throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory.Client(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();

        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port))
        {
            if (cn != null)
            {
                SNIHostName serverName = new SNIHostName(sniHost);
                List<SNIServerName> serverNames = new ArrayList<>();
                serverNames.add(serverName);

                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(serverNames);
                sslSocket.setSSLParameters(params);
            }
            sslSocket.startHandshake();

            if (cn != null)
            {
                X509Certificate cert = ((X509Certificate)sslSocket.getSession().getPeerCertificates()[0]);
                assertThat(cert.getSubjectX500Principal().getName("CANONICAL"), Matchers.startsWith("cn=" + cn));
            }

            try (OutputStream os = sslSocket.getOutputStream();
                 InputStream in = sslSocket.getInputStream())
            {
                String rawRequest = """
                    GET /ctx/path HTTP/1.1\r
                    Host: %s:%d\r
                    Connection: close\r
                    \r
                    """.formatted(reqHost, _port);

                os.write(rawRequest.getBytes(StandardCharsets.UTF_8));
                String rawResponse = IO.toString(in);
                return HttpTester.parseResponse(rawResponse);
            }
        }
        finally
        {
            clientContextFactory.stop();
        }
    }
}
