//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.ssl;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SocketCustomizationListener;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SniSslConnectionFactoryTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpConfiguration _https_config;
    private int _port;

    @Before
    public void before() throws Exception
    {
        String keystorePath = "src/test/resources/snikeystore";
        File keystoreFile = new File(keystorePath);
        if (!keystoreFile.exists())
            throw new FileNotFoundException(keystoreFile.getAbsolutePath());

        _server = new Server();

        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(8443);
        http_config.setOutputBufferSize(32768);
        _https_config = new HttpConfiguration(http_config);
        _https_config.addCustomizer(new SecureRequestCustomizer());

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(keystoreFile.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        ServerConnector https = _connector = new ServerConnector(_server,
                new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(_https_config));
        _server.addConnector(https);

        _server.setHandler(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setHeader("X-URL", request.getRequestURI());
                response.setHeader("X-HOST", request.getServerName());
            }
        });

        _server.start();
        _port = https.getLocalPort();
    }

    @After
    public void after() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testConnect() throws Exception
    {
        String response = getResponse("127.0.0.1", null);
        Assert.assertThat(response, Matchers.containsString("X-HOST: 127.0.0.1"));
    }

    @Test
    public void testSNIConnectNoWild() throws Exception
    {
        // Use the alternate keystore without wildcard certificates.
        _server.stop();
        _server.removeConnector(_connector);

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/snikeystore_nowild");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        _connector = new ServerConnector(_server,
                new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(_https_config));
        _server.addConnector(_connector);
        _server.start();
        _port = _connector.getLocalPort();

        // The first entry in the keystore is www.example.com, and it will
        // be returned by default, so make sure that here we don't ask for it.
        String response = getResponse("jetty.eclipse.org", "jetty.eclipse.org");
        Assert.assertThat(response, Matchers.containsString("X-HOST: jetty.eclipse.org"));
    }

    @Test
    public void testSNIConnect() throws Exception
    {
        String response = getResponse("jetty.eclipse.org", "jetty.eclipse.org");
        Assert.assertThat(response, Matchers.containsString("X-HOST: jetty.eclipse.org"));

        response = getResponse("www.example.com", "www.example.com");
        Assert.assertThat(response, Matchers.containsString("X-HOST: www.example.com"));

        response = getResponse("foo.domain.com", "*.domain.com");
        Assert.assertThat(response, Matchers.containsString("X-HOST: foo.domain.com"));

        response = getResponse("m.san.com", "san example");
        Assert.assertThat(response, Matchers.containsString("X-HOST: m.san.com"));

        response = getResponse("www.san.com", "san example");
        Assert.assertThat(response, Matchers.containsString("X-HOST: www.san.com"));
    }

    @Test
    public void testWildSNIConnect() throws Exception
    {
        String response = getResponse("domain.com", "www.domain.com", "*.domain.com");
        Assert.assertThat(response, Matchers.containsString("X-HOST: www.domain.com"));

        response = getResponse("domain.com", "domain.com", "*.domain.com");
        Assert.assertThat(response, Matchers.containsString("X-HOST: domain.com"));

        response = getResponse("www.domain.com", "www.domain.com", "*.domain.com");
        Assert.assertThat(response, Matchers.containsString("X-HOST: www.domain.com"));
    }

    @Test
    public void testBadSNIConnect() throws Exception
    {
        String response = getResponse("www.example.com", "some.other.com", "www.example.com");
        Assert.assertThat(response, Matchers.containsString("HTTP/1.1 400 "));
        Assert.assertThat(response, Matchers.containsString("Host does not match SNI"));
    }

    @Test
    public void testSameConnectionRequestsForManyDomains() throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port))
        {
            SNIHostName serverName = new SNIHostName("m.san.com");
            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(Collections.singletonList(serverName));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();

            // The first request binds the socket to an alias.
            String request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: m.san.com\r\n" +
                    "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = sslSocket.getInputStream();
            String response = response(input);
            Assert.assertTrue(response.startsWith("HTTP/1.1 200 "));

            // Same socket, send a request for a different domain but same alias.
            request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.san.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = response(input);
            Assert.assertTrue(response.startsWith("HTTP/1.1 200 "));

            // Same socket, send a request for a different domain but different alias.
            request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.example.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = response(input);
            assertThat(response,startsWith("HTTP/1.1 400 "));
            assertThat(response, containsString("Host does not match SNI"));
        }
        finally
        {
            clientContextFactory.stop();
        }
    }

    @Test
    public void testSameConnectionRequestsForManyWildDomains() throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port))
        {
            SNIHostName serverName = new SNIHostName("www.domain.com");
            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(Collections.singletonList(serverName));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();

            String request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.domain.com\r\n" +
                    "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = sslSocket.getInputStream();
            String response = response(input);
            Assert.assertTrue(response.startsWith("HTTP/1.1 200 "));

            // Now, on the same socket, send a request for a different valid domain.
            request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: assets.domain.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = response(input);
            Assert.assertTrue(response.startsWith("HTTP/1.1 200 "));

            // Now make a request for an invalid domain for this connection.
            request = "" +
                    "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.example.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = response(input);
            Assert.assertTrue(response.startsWith("HTTP/1.1 400 "));
            Assert.assertThat(response, Matchers.containsString("Host does not match SNI"));
        }
        finally
        {
            clientContextFactory.stop();
        }
    }

    private String response(InputStream input) throws IOException
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            Assert.assertTrue(read >= 0);
            buffer.append((byte)read);
            crlfs = (read == '\r' || read == '\n') ? crlfs + 1 : 0;
            if (crlfs == 4)
                break;
        }
        return buffer.toString();
    }

    private String getResponse(String host, String cn) throws Exception
    {
        String response = getResponse(host, host, cn);
        Assert.assertThat(response, Matchers.startsWith("HTTP/1.1 200 "));
        Assert.assertThat(response, Matchers.containsString("X-URL: /ctx/path"));
        return response;
    }

    private String getResponse(String sniHost, String reqHost, String cn) throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory(true);
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
                Assert.assertThat(cert.getSubjectX500Principal().getName("CANONICAL"), Matchers.startsWith("cn=" + cn));
            }

            String response = "GET /ctx/path HTTP/1.0\r\nHost: " + reqHost + ":" + _port + "\r\n\r\n";
            sslSocket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            return IO.toString(sslSocket.getInputStream());
        }
        finally
        {
            clientContextFactory.stop();
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

        String response = getResponse("127.0.0.1", null);
        Assert.assertThat(response, Matchers.containsString("X-HOST: 127.0.0.1"));

        Assert.assertEquals("customize connector class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        Assert.assertEquals("customize ssl class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        Assert.assertEquals("customize connector class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        Assert.assertEquals("customize http class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        Assert.assertEquals(0, history.size());
    }
}
