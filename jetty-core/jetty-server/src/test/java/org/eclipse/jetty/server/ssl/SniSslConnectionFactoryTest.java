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

package org.eclipse.jetty.server.ssl;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SocketCustomizationListener;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SniX509ExtendedKeyManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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

    private void start(String keystorePath) throws Exception
    {
        start(ssl -> ssl.setKeyStorePath(keystorePath));
    }

    private void start(Consumer<SslContextFactory.Server> sslConfig) throws Exception
    {
        start((ssl, customizer) -> sslConfig.accept(ssl));
    }

    private void start(BiConsumer<SslContextFactory.Server, SecureRequestCustomizer> config) throws Exception
    {
        _server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
        httpConfiguration.addCustomizer(secureRequestCustomizer);

        Handler.Wrapper xCertHandler = new Handler.Wrapper()
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                Request.Processor processor = getHandler().handle(request);
                if (processor == null)
                    return null;
                return (ignored, response, callback) ->
                {
                    EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                    SslConnection.DecryptedEndPoint sslEndPoint = (SslConnection.DecryptedEndPoint)endPoint;
                    SslConnection sslConnection = sslEndPoint.getSslConnection();
                    SSLEngine sslEngine = sslConnection.getSSLEngine();
                    SSLSession session = sslEngine.getSession();
                    for (Certificate c : session.getLocalCertificates())
                        response.getHeaders().add("X-CERT", ((X509Certificate)c).getSubjectDN().toString());
                    processor.process(request, response, callback);
                };
            }
        };

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        config.accept(sslContextFactory, secureRequestCustomizer);

        Path keystoreFile = sslContextFactory.getKeyStoreResource().getPath();
        if (!Files.exists(keystoreFile))
            throw new FileNotFoundException(keystoreFile.toAbsolutePath().toString());

        sslContextFactory.setKeyStorePassword("storepwd");

        _connector = new ServerConnector(_server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpConfiguration));
        _server.addConnector(_connector);
        _server.setHandler(xCertHandler);
        xCertHandler.setHandler(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().put("X-URL", Request.getPathInContext(request));
                response.getHeaders().put("X-HOST", Request.getServerName(request));
                callback.succeeded();
            }
        });

        _server.start();
    }

    @AfterEach
    public void after()
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testSNIConnectNoWild() throws Exception
    {
        start((ssl, customizer) ->
        {
            // Disable the host check because this keystore has no CN and no SAN.
            ssl.setKeyStorePath("src/test/resources/keystore_sni_nowild.p12");
            customizer.setSniHostCheck(false);
        });

        String response = getResponse("www.acme.org", null);
        assertThat(response, Matchers.containsString("X-HOST: www.acme.org"));
        assertThat(response, Matchers.containsString("X-CERT: OU=default"));

        response = getResponse("www.example.com", null);
        assertThat(response, Matchers.containsString("X-HOST: www.example.com"));
        assertThat(response, Matchers.containsString("X-CERT: OU=example"));
    }

    @Test
    public void testSNIConnect() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
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

        response = getResponse("wrongHost", "wrongHost", null);
        assertThat(response, Matchers.containsString("HTTP/1.1 400 "));
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
        assertThat(response, Matchers.containsString("Invalid SNI"));
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "See Issue #6609 - TLSv1.3 behavior differences between Linux and Windows")
    @Test
    public void testWrongSNIRejectedConnection() throws Exception
    {
        start(ssl ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
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
        start((ssl, customizer) ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            // Do not allow unmatched SNI.
            ssl.setSniRequired(false);
            customizer.setSniRequired(true);
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

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "See Issue #6609 - TLSv1.3 behavior differences between Linux and Windows")
    @Test
    public void testWrongSNIRejectedFunction() throws Exception
    {
        start((ssl, customizer) ->
        {
            ssl.setKeyStorePath("src/test/resources/keystore_sni.p12");
            // Do not allow unmatched SNI.
            ssl.setSniRequired(true);
            ssl.setSNISelector((keyType, issuers, session, sniHost, certificates) ->
            {
                if (sniHost == null)
                    return SniX509ExtendedKeyManager.SniSelector.DELEGATE;
                return ssl.sniSelect(keyType, issuers, session, sniHost, certificates);
            });
            customizer.setSniRequired(true);
        });

        // Wrong SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse("wrong.com", "wrong.com", null));

        // No SNI host.
        HttpTester.Response response = HttpTester.parseResponse(getResponse(null, "wrong.com", null));
        assertNotNull(response);
        assertThat(response.getStatus(), is(400));
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "See Issue #6609 - TLSv1.3 behavior differences between Linux and Windows")
    @Test
    public void testWrongSNIRejectedConnectionWithNonSNIKeystore() throws Exception
    {
        start(ssl ->
        {
            // Keystore has only one certificate, but we want to enforce SNI.
            ssl.setKeyStorePath("src/test/resources/keystore.p12");
            ssl.setSniRequired(true);
        });

        // Wrong SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse("wrong.com", "wrong.com", null));

        // No SNI host.
        assertThrows(SSLHandshakeException.class, () -> getResponse(null, "wrong.com", null));

        // Good SNI host.
        HttpTester.Response response = HttpTester.parseResponse(getResponse("localhost", "localhost", null));
        assertNotNull(response);
        assertThat(response.getStatus(), is(200));
    }

    @Test
    public void testSameConnectionRequestsForManyDomains() throws Exception
    {
        start("src/test/resources/keystore_sni.p12");

        SslContextFactory clientContextFactory = new SslContextFactory.Client(true);
        clientContextFactory.start();
        SSLSocketFactory factory = clientContextFactory.getSslContext().getSocketFactory();
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _connector.getLocalPort()))
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
            assertThat(response.getContent(), containsString("Invalid SNI"));
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
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _connector.getLocalPort()))
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
            assertThat(response.getContent(), containsString("Invalid SNI"));
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

        Queue<String> history = new LinkedBlockingQueue<>();

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

        String response = getResponse("www.example.com", null);
        assertThat(response, Matchers.containsString("X-HOST: www.example.com"));

        assertEquals("customize connector class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize ssl class org.eclipse.jetty.io.ssl.SslConnection,false", history.poll());
        assertEquals("customize connector class org.eclipse.jetty.server.internal.HttpConnection,true", history.poll());
        assertEquals("customize http class org.eclipse.jetty.server.internal.HttpConnection,true", history.poll());
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
        try (SSLSocket sslSocket = (SSLSocket)factory.createSocket("127.0.0.1", _connector.getLocalPort()))
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

            String response = "GET /ctx/path HTTP/1.0\r\nHost: " + reqHost + ":" + _connector.getLocalPort() + "\r\n\r\n";
            sslSocket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            return IO.toString(sslSocket.getInputStream());
        }
        finally
        {
            clientContextFactory.stop();
        }
    }
}
