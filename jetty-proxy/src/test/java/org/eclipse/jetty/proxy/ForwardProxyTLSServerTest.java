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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Principal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ForwardProxyTLSServerTest
{
    public static Stream<SslContextFactory.Server> proxyTLS()
    {
        return Stream.of(null, newProxySslContextFactory());
    }

    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private SslContextFactory.Server proxySslContextFactory;

    protected void startTLSServer(Handler handler) throws Exception
    {
        SslContextFactory.Server sslContextFactory = newServerSslContextFactory();
        startTLSServer(sslContextFactory, handler);
    }

    protected void startTLSServer(SslContextFactory.Server sslContextFactory, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        serverConnector = new ServerConnector(server, sslContextFactory);
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    protected void startProxy(SslContextFactory.Server proxyTLS) throws Exception
    {
        startProxy(proxyTLS, new ConnectHandler());
    }

    protected void startProxy(SslContextFactory.Server proxyTLS, ConnectHandler connectHandler) throws Exception
    {
        proxySslContextFactory = proxyTLS;
        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);
        proxyConnector = new ServerConnector(proxy, proxyTLS);
        proxy.addConnector(proxyConnector);
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        connectHandler.setConnectTimeout(1000);
        proxy.setHandler(connectHandler);
        proxy.start();
    }

    protected HttpProxy newHttpProxy()
    {
        // Use an address to avoid resolution of "localhost" to multiple addresses.
        return new HttpProxy(new Origin.Address("127.0.0.1", proxyConnector.getLocalPort()), proxySslContextFactory != null);
    }

    private HttpClient newHttpClient()
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        return new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
    }

    private static SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("server_keystore.p12").getAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        return sslContextFactory;
    }

    private static SslContextFactory.Server newProxySslContextFactory()
    {
        SslContextFactory.Server proxyTLS = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("proxy_keystore.p12").getAbsolutePath();
        proxyTLS.setKeyStorePath(keyStorePath);
        proxyTLS.setKeyStorePassword("storepwd");
        return proxyTLS;
    }

    private static SslContextFactory.Client newClientSslContextFactory()
    {
        return new SslContextFactory.Client(true);
    }

    @AfterEach
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    protected void stopProxy() throws Exception
    {
        if (proxy != null)
        {
            proxy.stop();
            proxy.join();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testOneExchange(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        try
        {
            // Use a numeric host to test the URI of the CONNECT request.
            // URIs such as host:80 may interpret "host" as the scheme,
            // but when the host is numeric it is not a valid URI.
            String host = "127.0.0.1";
            String body = "BODY";
            ContentResponse response = httpClient.newRequest(host, serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testTwoExchanges(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response1 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response1.getStatus());
            String content = response1.getContentAsString();
            assertEquals(body, content);

            content = "body=" + body;
            int contentLength = content.length();
            ContentResponse response2 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.POST)
                .path("/echo")
                .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString()))
                .headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength)))
                .body(new StringRequestContent(content))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response2.getStatus());
            content = response2.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testTwoConcurrentExchanges(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        try
        {
            AtomicReference<Connection> connection = new AtomicReference<>();
            CountDownLatch connectionLatch = new CountDownLatch(1);
            String content1 = "BODY";
            ContentResponse response1 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(content1, StandardCharsets.UTF_8))
                .onRequestCommit(request ->
                {
                    Destination destination = httpClient.resolveDestination(request);
                    destination.newConnection(new Promise.Adapter<>()
                    {
                        @Override
                        public void succeeded(Connection result)
                        {
                            connection.set(result);
                            connectionLatch.countDown();
                        }
                    });
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response1.getStatus());
            String content = response1.getContentAsString();
            assertEquals(content1, content);

            assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));

            String body2 = "body=" + content1;
            org.eclipse.jetty.client.api.Request request2 = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.POST)
                .path("/echo")
                .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString()))
                .headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, String.valueOf(body2.length())))
                .body(new StringRequestContent(body2));

            // Make sure the second connection can send the exchange via the tunnel
            FutureResponseListener listener2 = new FutureResponseListener(request2);
            connection.get().send(request2, listener2);
            ContentResponse response2 = listener2.get(5, TimeUnit.SECONDS);

            assertEquals(HttpStatus.OK_200, response2.getStatus());
            String content2 = response2.getContentAsString();
            assertEquals(content1, content2);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testShortIdleTimeoutOverriddenByRequest(SslContextFactory.Server proxyTLS) throws Exception
    {
        // Short idle timeout for HttpClient.
        long idleTimeout = 500;

        startTLSServer(new ServerHandler());
        startProxy(proxyTLS, new ConnectHandler()
        {
            @Override
            protected void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress)
            {
                try
                {
                    // Make sure the proxy remains idle enough.
                    sleep(2 * idleTimeout);
                    super.handleConnect(baseRequest, request, response, serverAddress);
                }
                catch (Throwable x)
                {
                    onConnectFailure(request, response, null, x);
                }
            }
        });

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        // Short idle timeout for HttpClient.
        httpClient.setIdleTimeout(idleTimeout);
        httpClient.start();

        try
        {
            String host = "localhost";
            String body = "BODY";
            ContentResponse response = httpClient.newRequest(host, serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                // Long idle timeout for the request.
                .idleTimeout(10 * idleTimeout, TimeUnit.MILLISECONDS)
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyDown(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);
        int proxyPort = proxyConnector.getLocalPort();
        stopProxy();

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(new HttpProxy(new Origin.Address("localhost", proxyPort), proxySslContextFactory != null));
        httpClient.start();

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            String body = "BODY";
            httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertThat(x.getCause(), instanceOf(ConnectException.class));

        httpClient.stop();
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testServerDown(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        int serverPort = serverConnector.getLocalPort();
        stopServer();
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        assertThrows(ExecutionException.class, () ->
        {
            String body = "BODY";
            httpClient.newRequest("localhost", serverPort)
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });

        httpClient.stop();
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyClosesConnection(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS, new ConnectHandler()
        {
            @Override
            protected void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress)
            {
                ((HttpConnection)baseRequest.getHttpChannel().getHttpTransport()).close();
            }
        });

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        assertThrows(ExecutionException.class, () ->
            httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        httpClient.stop();
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testIPv6(SslContextFactory.Server proxyTLS) throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());

        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        HttpProxy httpProxy = new HttpProxy(new Origin.Address("[::1]", proxyConnector.getLocalPort()), proxyTLS != null);
        httpClient.getProxyConfiguration().addProxy(httpProxy);
        httpClient.start();

        try
        {
            ContentResponse response = httpClient.newRequest("[::1]", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .path("/echo?body=")
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyAuthentication(SslContextFactory.Server proxyTLS) throws Exception
    {
        String realm = "test-realm";
        testProxyAuthentication(proxyTLS, new ConnectHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String proxyAuth = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (proxyAuth == null)
                {
                    baseRequest.setHandled(true);
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + realm + "\"");
                    return;
                }
                super.handle(target, baseRequest, request, response);
            }
        }, realm);
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyAuthenticationWithResponseContent(SslContextFactory.Server proxyTLS) throws Exception
    {
        String realm = "test-realm";
        testProxyAuthentication(proxyTLS, new ConnectHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String proxyAuth = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (proxyAuth == null)
                {
                    baseRequest.setHandled(true);
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + realm + "\"");
                    response.getOutputStream().write(new byte[4096]);
                    return;
                }
                super.handle(target, baseRequest, request, response);
            }
        }, realm);
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyAuthenticationWithIncludedAddressWithResponseContent(SslContextFactory.Server proxyTLS) throws Exception
    {
        String realm = "test-realm";
        testProxyAuthentication(proxyTLS, new ConnectHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String proxyAuth = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString());
                if (proxyAuth == null)
                {
                    baseRequest.setHandled(true);
                    response.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString(), "Basic realm=\"" + realm + "\"");
                    response.getOutputStream().write(new byte[1024]);
                    return;
                }
                super.handle(target, baseRequest, request, response);
            }
        }, realm, true);
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyAuthenticationClosesConnection(SslContextFactory.Server proxyTLS) throws Exception
    {
        String realm = "test-realm";
        testProxyAuthentication(proxyTLS, new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address)
            {
                String header = request.getHeader(HttpHeader.PROXY_AUTHORIZATION.toString());
                if (header == null || !header.startsWith("Basic "))
                {
                    response.setHeader(HttpHeader.PROXY_AUTHENTICATE.toString(), "Basic realm=\"" + realm + "\"");
                    // Returning false adds Connection: close to the 407 response.
                    return false;
                }
                else
                {
                    return true;
                }
            }
        }, realm);
    }

    private void testProxyAuthentication(SslContextFactory.Server proxyTLS, ConnectHandler connectHandler, String realm) throws Exception
    {
        testProxyAuthentication(proxyTLS, connectHandler, realm, false);
    }

    private void testProxyAuthentication(SslContextFactory.Server proxyTLS, ConnectHandler connectHandler, String realm, boolean includeAddress) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS, connectHandler);

        HttpClient httpClient = newHttpClient();
        HttpProxy httpProxy = newHttpProxy();
        if (includeAddress)
            httpProxy.getIncludedAddresses().add("localhost:" + serverConnector.getLocalPort());
        httpClient.getProxyConfiguration().addProxy(httpProxy);
        URI uri = URI.create((proxySslContextFactory == null ? "http" : "https") + "://127.0.0.1:" + proxyConnector.getLocalPort());
        httpClient.getAuthenticationStore().addAuthentication(new BasicAuthentication(uri, realm, "proxyUser", "proxyPassword"));
        httpClient.start();

        try
        {
            String host = "localhost";
            String body = "BODY";
            ContentResponse response = httpClient.newRequest(host, serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testBothProxyAndServerNeedClientAuth() throws Exception
    {
        // See src/test/resources/readme_keystores.txt.

        SslContextFactory.Server serverTLS = newServerSslContextFactory();
        serverTLS.setNeedClientAuth(true);
        startTLSServer(serverTLS, new ServerHandler());
        int serverPort = serverConnector.getLocalPort();

        SslContextFactory.Server proxyTLS = newProxySslContextFactory();
        proxyTLS.setEndpointIdentificationAlgorithm(null);
        proxyTLS.setNeedClientAuth(true);
        startProxy(proxyTLS);
        int proxyPort = proxyConnector.getLocalPort();

        String proxyAlias = "client_to_proxy";
        String serverAlias = "client_to_server";
        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client()
        {
            @Override
            protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
            {
                KeyManager[] keyManagers = super.getKeyManagers(keyStore);
                for (int i = 0; i < keyManagers.length; i++)
                {
                    KeyManager keyManager = keyManagers[i];
                    if (keyManager instanceof X509ExtendedKeyManager)
                    {
                        X509ExtendedKeyManager extKeyManager = (X509ExtendedKeyManager)keyManager;
                        keyManagers[i] = new X509ExtendedKeyManagerWrapper(extKeyManager)
                        {
                            @Override
                            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine)
                            {
                                int port = engine.getPeerPort();
                                if (port == serverPort)
                                    return serverAlias;
                                else if (port == proxyPort)
                                    return proxyAlias;
                                else
                                    return super.chooseEngineClientAlias(keyType, issuers, engine);
                            }
                        };
                    }
                }
                return keyManagers;
            }
        };
        clientSslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_keystore.p12").getAbsolutePath());
        clientSslContextFactory.setKeyStorePassword("storepwd");
        clientSslContextFactory.setEndpointIdentificationAlgorithm(null);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(clientSslContextFactory);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testBothProxyAndServerNeedClientAuthWithDifferentKeyStores() throws Exception
    {
        SslContextFactory.Server serverTLS = newServerSslContextFactory();
        serverTLS.setEndpointIdentificationAlgorithm(null);
        serverTLS.setNeedClientAuth(true);
        startTLSServer(serverTLS, new ServerHandler());
        int serverPort = serverConnector.getLocalPort();

        SslContextFactory.Server proxyServerTLS = newProxySslContextFactory();
        proxyServerTLS.setEndpointIdentificationAlgorithm(null);
        proxyServerTLS.setNeedClientAuth(true);
        startProxy(proxyServerTLS);
        int proxyPort = proxyConnector.getLocalPort();

        SslContextFactory.Client clientTLS = new SslContextFactory.Client()
        {
            @Override
            public SSLEngine newSSLEngine(String host, int port)
            {
                if (port != serverPort)
                    throw new IllegalStateException();
                return super.newSSLEngine(host, port);
            }
        };
        clientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_server_keystore.p12").getAbsolutePath());
        clientTLS.setKeyStorePassword("storepwd");
        clientTLS.setEndpointIdentificationAlgorithm(null);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(clientTLS);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));

        SslContextFactory.Client proxyClientTLS = new SslContextFactory.Client()
        {
            @Override
            public SSLEngine newSSLEngine(String host, int port)
            {
                if (port != proxyPort)
                    throw new IllegalStateException();
                return super.newSSLEngine(host, port);
            }
        };
        proxyClientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_proxy_keystore.p12").getAbsolutePath());
        proxyClientTLS.setKeyStorePassword("storepwd");
        proxyClientTLS.setEndpointIdentificationAlgorithm(null);
        proxyClientTLS.start();
        HttpProxy httpProxy = new HttpProxy(new Origin.Address("localhost", proxyConnector.getLocalPort()), proxyClientTLS);
        httpClient.getProxyConfiguration().addProxy(httpProxy);
        httpClient.start();

        try
        {
            String body = "BODY";
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testRequestCompletionDelayed(SslContextFactory.Server proxyTLS) throws Exception
    {
        startTLSServer(new ServerHandler());
        startProxy(proxyTLS);

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.start();

        try
        {
            httpClient.getRequestListeners().add(new org.eclipse.jetty.client.api.Request.Listener()
            {
                @Override
                public void onSuccess(org.eclipse.jetty.client.api.Request request)
                {
                    if (HttpMethod.CONNECT.is(request.getMethod()))
                        sleep(250);
                }
            });

            String body = "BODY";
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .method(HttpMethod.GET)
                .path("/echo?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testServerLongProcessing(SslContextFactory.Server proxyTLS) throws Exception
    {
        long timeout = 500;
        startTLSServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(3 * timeout);
                baseRequest.setHandled(true);
            }
        });
        startProxy(proxyTLS);
        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.setConnectTimeout(timeout);
        httpClient.setIdleTimeout(4 * timeout);
        httpClient.start();

        try
        {
            // The idle timeout is larger than the server processing time, request should succeed.
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testServerLongProcessingWithRequestIdleTimeout(SslContextFactory.Server proxyTLS) throws Exception
    {
        long timeout = 500;
        startTLSServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                sleep(3 * timeout);
                baseRequest.setHandled(true);
            }
        });
        startProxy(proxyTLS);
        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.setConnectTimeout(timeout);
        // Short idle timeout for HttpClient.
        httpClient.setIdleTimeout(timeout);
        httpClient.start();

        try
        {
            // The idle timeout is larger than the server processing time, request should succeed.
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                // Long idle timeout for the request, should override that of the client.
                .idleTimeout(4 * timeout, TimeUnit.MILLISECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("proxyTLS")
    public void testProxyLongProcessing(SslContextFactory.Server proxyTLS) throws Exception
    {
        long timeout = 500;
        startTLSServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
            }
        });
        startProxy(proxyTLS, new ConnectHandler()
        {
            @Override
            protected void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress)
            {
                sleep(3 * timeout);
                super.handleConnect(baseRequest, request, response, serverAddress);
            }
        });
        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(newHttpProxy());
        httpClient.setConnectTimeout(timeout);
        httpClient.setIdleTimeout(10 * timeout);
        httpClient.start();

        try
        {
            // Connecting to the server through the proxy involves a CONNECT + 200
            // so if the proxy delays the response, the client request interprets
            // it as a "connect" timeout (rather than an idle timeout).
            AtomicReference<Result> resultRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            httpClient.newRequest("localhost", serverConnector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .send(result ->
                {
                    resultRef.set(result);
                    latch.countDown();
                });

            assertTrue(latch.await(2 * timeout, TimeUnit.MILLISECONDS));
            Result result = resultRef.get();
            assertTrue(result.isFailed());
            assertThat(result.getFailure(), instanceOf(TimeoutException.class));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    @Tag("external")
    @Disabled
    public void testExternalProxy() throws Exception
    {
        // Free proxy server obtained from http://hidemyass.com/proxy-list/
        String proxyHost = "81.208.25.53";
        int proxyPort = 3128;
        try
        {
            new Socket(proxyHost, proxyPort).close();
        }
        catch (Throwable x)
        {
            assumeTrue(false, "Environment not able to connect to proxy service");
        }

        HttpClient httpClient = newHttpClient();
        httpClient.getProxyConfiguration().addProxy(new HttpProxy(proxyHost, proxyPort));
        httpClient.start();

        try
        {
            ContentResponse response = httpClient.newRequest("https://www.google.com")
                // Use a longer timeout, sometimes the proxy takes a while to answer
                .timeout(20, TimeUnit.SECONDS)
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private static class ServerHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
            {
                String body = httpRequest.getParameter("body");
                ServletOutputStream output = httpResponse.getOutputStream();
                output.print(body);
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
