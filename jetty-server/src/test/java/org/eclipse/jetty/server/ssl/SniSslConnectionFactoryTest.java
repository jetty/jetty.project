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

package org.eclipse.jetty.server.ssl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SocketCustomizationListener;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SniX509ExtendedKeyManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SniSslConnectionFactoryTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpConfiguration _httpsConfiguration;
    private int _port;

    @BeforeEach
    public void before()
    {
        _server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(8443);
        httpConfig.setOutputBufferSize(32768);
        _httpsConfiguration = new HttpConfiguration(httpConfig);
        SecureRequestCustomizer src = new SecureRequestCustomizer();
        src.setSniHostCheck(true);
        _httpsConfiguration.addCustomizer(src);
        _httpsConfiguration.addCustomizer((connector, hc, request) ->
        {
            EndPoint endp = request.getHttpChannel().getEndPoint();
            if (endp instanceof SslConnection.DecryptedEndPoint)
            {
                try
                {
                    SslConnection.DecryptedEndPoint sslEndp = (SslConnection.DecryptedEndPoint)endp;
                    SslConnection sslConnection = sslEndp.getSslConnection();
                    SSLEngine sslEngine = sslConnection.getSSLEngine();
                    SSLSession session = sslEngine.getSession();
                    for (Certificate c : session.getLocalCertificates())
                    {
                        request.getResponse().getHttpFields().add("X-Cert", ((X509Certificate)c).getSubjectDN().toString());
                    }
                }
                catch (Throwable th)
                {
                    th.printStackTrace();
                }
            }
        });
    }

    protected void start(String keystorePath) throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath(keystorePath);
            ssl.setKeyManagerPassword("keypwd");
        });
    }

    protected void start(Consumer<SslContextFactory.Server> sslConfig) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePassword("storepwd");
        sslConfig.accept(sslContextFactory);

        File keystoreFile = sslContextFactory.getKeyStoreResource().getFile();
        if (!keystoreFile.exists())
            throw new FileNotFoundException(keystoreFile.getAbsolutePath());

        ServerConnector https = _connector = new ServerConnector(_server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(_httpsConfiguration));
        _server.addConnector(https);

        _server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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

    @AfterEach
    public void after() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testConnect() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");
        String response = getResponse("127.0.0.1", null);
        assertThat(response, Matchers.containsString("X-HOST: 127.0.0.1"));
    }

    @Test
    public void testSNIConnectNoWild() throws Exception
    {
        start("src/test/resources/keystore_sni_nowild.p12");

        String response = getResponse("www.acme.org", null);
        assertThat(response, Matchers.containsString("X-HOST: www.acme.org"));
        assertThat(response, Matchers.containsString("X-Cert: OU=default"));

        response = getResponse("www.example.com", null);
        assertThat(response, Matchers.containsString("X-HOST: www.example.com"));
        assertThat(response, Matchers.containsString("X-Cert: OU=example"));
    }

    @Test
    public void testSNIConnect() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            ssl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
            ssl.setSNISelector((keyType, issuers, session, sniHost, certificates) ->
            {
                // Make sure the *.domain.com comes before sub.domain.com
                // to test that we prefer more specific domains.
                List<X509> sortedCertificates = certificates.stream()
                    // As sorted() sorts ascending, make *.domain.com the smallest.
                    .sorted((x509a, x509b) ->
                    {
                        if (x509a.matches("domain.com"))
                            return -1;
                        if (x509b.matches("domain.com"))
                            return 1;
                        return 0;
                    })
                    .collect(Collectors.toList());
                return ssl.sniSelect(keyType, issuers, session, sniHost, sortedCertificates);
            });
        });

        String response = getResponse("jetty.eclipse.org", "jetty.eclipse.org");
        assertThat(response, Matchers.containsString("X-HOST: jetty.eclipse.org"));

        response = getResponse("www.example.com", "www.example.com");
        assertThat(response, Matchers.containsString("X-HOST: www.example.com"));

        response = getResponse("foo.domain.com", "*.domain.com");
        assertThat(response, Matchers.containsString("X-HOST: foo.domain.com"));

        response = getResponse("sub.domain.com", "sub.domain.com");
        assertThat(response, Matchers.containsString("X-HOST: sub.domain.com"));

        response = getResponse("m.san.com", "san example");
        assertThat(response, Matchers.containsString("X-HOST: m.san.com"));

        response = getResponse("www.san.com", "san example");
        assertThat(response, Matchers.containsString("X-HOST: www.san.com"));
    }

    @Test
    public void testWildSNIConnect() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");

        String response = getResponse("domain.com", "www.domain.com", "*.domain.com");
        assertThat(response, Matchers.containsString("X-HOST: www.domain.com"));

        response = getResponse("domain.com", "domain.com", "*.domain.com");
        assertThat(response, Matchers.containsString("X-HOST: domain.com"));

        response = getResponse("www.domain.com", "www.domain.com", "*.domain.com");
        assertThat(response, Matchers.containsString("X-HOST: www.domain.com"));
    }

    @Test
    public void testBadSNIConnect() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");

        String response = getResponse("www.example.com", "some.other.com", "www.example.com");
        assertThat(response, Matchers.containsString("HTTP/1.1 400 "));
        assertThat(response, Matchers.containsString("Host does not match SNI"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testWrongSNIRejectedConnection() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            ssl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
            // Do not allow unmatched SNI.
            ssl.setSniRequired(true);
        });

        // Wrong SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse("wrong.com", "wrong.com", null));

        // No SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse(null, "wrong.com", null));
    }

    @Test
    public void testWrongSNIRejectedBadRequest() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            ssl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
            // Do not allow unmatched SNI.
            ssl.setSniRequired(false);
            _httpsConfiguration.getCustomizers().stream()
                .filter(SecureRequestCustomizer.class::isInstance)
                .map(SecureRequestCustomizer.class::cast)
                .forEach(src -> src.setSniRequired(true));
        });

        // Wrong SNI host.
        HttpTester.Response response = HttpTester.parseResponse(getResponse("wrong.com", "wrong.com", null));
        assertNotNull(response);
        assertThat(response.getStatus(), is(400));

        // No SNI host.
        response = HttpTester.parseResponse(getResponse(null, "wrong.com", null));
        assertNotNull(response);
        assertThat(response.getStatus(), is(400));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testWrongSNIRejectedFunction() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            ssl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
            // Do not allow unmatched SNI.
            ssl.setSniRequired(true);
            ssl.setSNISelector((keyType, issuers, session, sniHost, certificates) ->
            {
                if (sniHost == null)
                    return SniX509ExtendedKeyManager.SniSelector.DELEGATE;
                return ssl.sniSelect(keyType, issuers, session, sniHost, certificates);
            });
            _httpsConfiguration.getCustomizers().stream()
                .filter(SecureRequestCustomizer.class::isInstance)
                .map(SecureRequestCustomizer.class::cast)
                .forEach(src -> src.setSniRequired(true));
        });

        // Wrong SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse("wrong.com", "wrong.com", null));

        // No SNI host.
        HttpTester.Response response = HttpTester.parseResponse(getResponse(null, "wrong.com", null));
        assertNotNull(response);
        assertThat(response.getStatus(), is(400));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testWrongSNIRejectedConnectionWithNonSNIKeystore() throws Exception
    {
        start(ssl ->
        {
            // Keystore has only one certificate, but we want to enforce SNI.
            ssl.setKeyStorePath("src/test/resources/keystore");
            ssl.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
            ssl.setSniRequired(true);
        });

        // Wrong SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse("wrong.com", "wrong.com", null));

        // No SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse(null, "wrong.com", null));

        // Good SNI host.
        HttpTester.Response response = HttpTester.parseResponse(getResponse("jetty.eclipse.org", "jetty.eclipse.org", null));

        assertNotNull(response);
        assertThat(response.getStatus(), is(200));
    }

    @Test
    public void testSameConnectionRequestsForManyDomains() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");
        _server.setErrorHandler(new ErrorHandler());

        SslContextFactory clientContextFactory = new SslContextFactory.Client(true);
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
            String request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: m.san.com\r\n" +
                    "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = sslSocket.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(200));

            // Same socket, send a request for a different domain but same alias.
            request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.san.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(200));

            // Same socket, send a request for a different domain but different alias.
            request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.example.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(400));
            assertThat(response.getContent(), containsString("Host does not match SNI"));
        }
        finally
        {
            clientContextFactory.stop();
        }
    }

    @Test
    public void testSameConnectionRequestsForManyWildDomains() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");

        SslContextFactory clientContextFactory = new SslContextFactory.Client(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port))
        {
            SNIHostName serverName = new SNIHostName("www.domain.com");
            SSLParameters params = sslSocket.getSSLParameters();
            params.setServerNames(Collections.singletonList(serverName));
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();

            String request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.domain.com\r\n" +
                    "\r\n";
            OutputStream output = sslSocket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = sslSocket.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(200));

            // Now, on the same socket, send a request for a different valid domain.
            request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: assets.domain.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(200));

            // Now make a request for an invalid domain for this connection.
            request =
                "GET /ctx/path HTTP/1.1\r\n" +
                    "Host: www.example.com\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertThat(response.getStatus(), is(400));
            assertThat(response.getContent(), containsString("Host does not match SNI"));
        }
        finally
        {
            clientContextFactory.stop();
        }
    }

    @Test
    public void testSocketCustomization() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");

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
        assertThat(response, Matchers.containsString("X-HOST: 127.0.0.1"));

        assertEquals("customize connector class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize ssl class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize connector class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        assertEquals("customize http class org.eclipse.jetty.server.HttpConnection,true", history.poll());
        assertEquals(0, history.size());
    }

    @Test
    public void testSNIWithDifferentKeyTypes() throws Exception
    {
        // This KeyStore contains 2 certificates, one with keyAlg=EC, one with keyAlg=RSA.
        start(ssl -> ssl.setKeyStorePath("src/test/resources/keystore_sni_key_types.p12"));

        // Make a request with SNI = rsa.domain.com, the RSA certificate should be chosen.
        HttpTester.Response response1 = HttpTester.parseResponse(getResponse("rsa.domain.com", "rsa.domain.com"));
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Make a request with SNI = ec.domain.com, the EC certificate should be chosen.
        HttpTester.Response response2 = HttpTester.parseResponse(getResponse("ec.domain.com", "ec.domain.com"));
        assertEquals(HttpStatus.OK_200, response2.getStatus());
    }

    private String getResponse(String host, String cn) throws Exception
    {
        String response = getResponse(host, host, cn);
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("X-URL: /ctx/path"));
        return response;
    }

    private String getResponse(String sniHost, String reqHost, String cn) throws Exception
    {
        SslContextFactory clientContextFactory = new SslContextFactory.Client(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _port))
        {
            if (sniHost != null)
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

            String response = "GET /ctx/path HTTP/1.0\r\nHost: " + reqHost + ":" + _port + "\r\n\r\n";
            sslSocket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            return IO.toString(sslSocket.getInputStream());
        }
        finally
        {
            clientContextFactory.stop();
        }
    }
}
