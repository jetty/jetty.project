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

package org.eclipse.jetty.ee10.proxy;

import java.security.KeyStore;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * <p>Tests for client and proxy authentication using certificates.</p>
 * <p>There are 3 KeyStores:</p>
 * <dl>
 *   <dt>{@code client_keystore.p12}</dt>
 *   <dd>{@code server} -> the server certificate with CN=server</dd>
 *   <dd>{@code user1_client} -> the client certificate for user1, signed with the server certificate</dd>
 *   <dd>{@code user2_client} -> the client certificate for user2, signed with the server certificate</dd>
 * </dl>
 * <dl>
 *   <dt>{@code proxy_keystore.p12}</dt>
 *   <dd>{@code proxy} -> the proxy domain private key and certificate with CN=proxy</dd>
 *   <dd>{@code server} -> the server domain certificate with CN=server</dd>
 *   <dd>{@code user1_proxy} -> the proxy client certificate for user1, signed with the server certificate</dd>
 *   <dd>{@code user2_proxy} -> the proxy client certificate for user2, signed with the server certificate</dd>
 * </dl>
 * <dl>
 *   <dt>{@code server_keystore.p12}</dt>
 *   <dd>{@code server} -> the server domain private key and certificate with CN=server,
 *   with extension ca:true to sign client and proxy certificates.</dd>
 * </dl>
 * <p>In this way, a remote client can connect to the proxy and be authenticated,
 * and the proxy can connect to the server on behalf of that remote client, since
 * the proxy has a certificate correspondent to the one of the remote client.</p>
 * <p>The main problem is to make sure that the {@code HttpClient} in the proxy uses different connections
 * to connect to the same server, and that those connections are authenticated via TLS client certificate
 * with the correct certificate, avoiding that requests made by {@code user2} are sent over connections
 * that are authenticated with {@code user1} certificates.</p>
 */
public class ClientAuthProxyTest
{
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        HttpConfiguration httpConfig = new HttpConfiguration();
        SecureRequestCustomizer customizer = new SecureRequestCustomizer();
        customizer.setSniRequired(false);
        customizer.setSniHostCheck(false);
        httpConfig.addCustomizer(customizer);
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);

        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setSniRequired(false);
        serverTLS.setNeedClientAuth(true);
        // The KeyStore is also a TrustStore.
        serverTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/server_keystore.p12").getAbsolutePath());
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setKeyStoreType("PKCS12");

        SslConnectionFactory ssl = new SslConnectionFactory(serverTLS, http.getProtocol());

        serverConnector = new ServerConnector(server, 1, 1, ssl, http);
        server.addConnector(serverConnector);

        server.setHandler(handler);

        server.start();
        System.err.println("SERVER = localhost:" + serverConnector.getLocalPort());
    }

    private void startServer() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                X509Certificate[] certificates = (X509Certificate[])request.getAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE);
                Assertions.assertNotNull(certificates);
                X509Certificate certificate = certificates[0];
                X500Principal principal = certificate.getSubjectX500Principal();
                String body = "%s\r\n%d\r\n".formatted(principal.toString(), org.eclipse.jetty.server.Request.getRemotePort(request));
                Content.Sink.write(response, true, body, callback);
            }
        });
    }

    private void startProxy(AbstractProxyServlet servlet) throws Exception
    {
        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        SecureRequestCustomizer customizer = new SecureRequestCustomizer();
        customizer.setSniRequired(false);
        customizer.setSniHostCheck(false);
        httpConfig.addCustomizer(customizer);
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);

        SslContextFactory.Server proxyTLS = new SslContextFactory.Server();
        proxyTLS.setSniRequired(false);
        proxyTLS.setNeedClientAuth(true);
        // The KeyStore is also a TrustStore.
        proxyTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/proxy_keystore.p12").getAbsolutePath());
        proxyTLS.setKeyStorePassword("storepwd");
        proxyTLS.setKeyStoreType("PKCS12");

        SslConnectionFactory ssl = new SslConnectionFactory(proxyTLS, http.getProtocol());

        proxyConnector = new ServerConnector(proxy, 1, 1, ssl, http);
        proxy.addConnector(proxyConnector);

        ServletContextHandler context = new ServletContextHandler(proxy, "/");
        context.addServlet(new ServletHolder(servlet), "/*");
        proxy.setHandler(context);

        proxy.start();
        System.err.println("PROXY = localhost:" + proxyConnector.getLocalPort());
    }

    private void startClient() throws Exception
    {
        SslContextFactory.Client clientTLS = new SslContextFactory.Client();
        // Disable TLS-level hostname verification.
        clientTLS.setEndpointIdentificationAlgorithm(null);
        clientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/client_keystore.p12").getAbsolutePath());
        clientTLS.setKeyStorePassword("storepwd");
        clientTLS.setKeyStoreType("PKCS12");
        ClientConnector connector = new ClientConnector();
        connector.setSelectors(1);
        connector.setSslContextFactory(clientTLS);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        connector.setExecutor(clientThreads);
        client = new HttpClient(new HttpClientTransportDynamic(connector));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(proxy);
        LifeCycle.stop(server);
    }

    private static String retrieveUser(HttpServletRequest request)
    {
        X509Certificate[] certificates = (X509Certificate[])request.getAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE);
        String clientName = certificates[0].getSubjectX500Principal().getName();
        Matcher matcher = Pattern.compile("CN=([^,]+)").matcher(clientName);
        if (matcher.find())
        {
            // Retain only "userN".
            return matcher.group(1).split("_")[0];
        }
        return null;
    }

    @Test
    public void testClientAuthProxyingWithMultipleHttpClients() throws Exception
    {
        // Using a different HttpClient (with a different SslContextFactory.Client)
        // per user works, but there is a lot of duplicated state in the HttpClients:
        // Executors and Schedulers (although they can be shared), but also CookieManagers
        // ProtocolHandlers, etc.
        // The proxy has different SslContextFactory.Client statically configured
        // for different users.

        startServer();
        startProxy(new AsyncProxyServlet()
        {
            private final Map<String, HttpClient> httpClients = new ConcurrentHashMap<>();

            @Override
            protected Request newProxyRequest(HttpServletRequest request, String rewrittenTarget)
            {
                String user = retrieveUser(request);
                HttpClient httpClient = getOrCreateHttpClient(user);
                Request proxyRequest = httpClient.newRequest(rewrittenTarget)
                    .scheme(HttpScheme.HTTPS.asString())
                    .method(request.getMethod())
                    .attribute(CLIENT_REQUEST_ATTRIBUTE, request);
                // Send the request to the server.
                proxyRequest.port(serverConnector.getLocalPort());
                // No need to tag the request when using different HttpClients.
                return proxyRequest;
            }

            private HttpClient getOrCreateHttpClient(String user)
            {
                if (user == null)
                    return getHttpClient();
                return httpClients.computeIfAbsent(user, key ->
                {
                    SslContextFactory.Client clientTLS = new SslContextFactory.Client();
                    // Disable TLS-level hostname verification for this test.
                    clientTLS.setEndpointIdentificationAlgorithm(null);
                    clientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/proxy_keystore.p12").getAbsolutePath());
                    clientTLS.setKeyStorePassword("storepwd");
                    clientTLS.setKeyStoreType("PKCS12");
                    clientTLS.setCertAlias(key + "_proxy");
                    // TODO: httpClients should share the ClientConnector.
                    ClientConnector connector = new ClientConnector();
                    connector.setSelectors(1);
                    connector.setSslContextFactory(clientTLS);
                    HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(connector));
                    LifeCycle.start(httpClient);
                    return httpClient;
                });
            }
        });
        startClient();

        testRequestsFromRemoteClients();
    }

    @Test
    public void testClientAuthProxyingWithMultipleServerSubDomains() throws Exception
    {
        // Another idea is to use multiple subdomains for the server,
        // such as user1.server.com, user2.server.com, with the server
        // providing a *.server.com certificate.
        // The proxy must pick the right alias dynamically based on the
        // remote client request.
        // For this test we use 127.0.0.N addresses.

        startServer();
        startProxy(new AsyncProxyServlet()
        {
            private final AtomicInteger userIds = new AtomicInteger();
            private final Map<String, String> subDomains = new ConcurrentHashMap<>();

            @Override
            protected Request newProxyRequest(HttpServletRequest request, String rewrittenTarget)
            {
                String user = retrieveUser(request);
                // Obviously not fool proof, but for the 2 users in this test it does the job.
                String subDomain = subDomains.computeIfAbsent(user, key -> "127.0.0." + userIds.incrementAndGet());
                Request proxyRequest = super.newProxyRequest(request, rewrittenTarget);
                proxyRequest.scheme(HttpScheme.HTTPS.asString()).host(subDomain).port(serverConnector.getLocalPort());
                // Tag the request.
                proxyRequest.tag(new AliasTLSTag(user));
                return proxyRequest;
            }

            @Override
            protected HttpClient newHttpClient()
            {
                SslContextFactory.Client clientTLS = new SslContextFactory.Client()
                {
                    @Override
                    protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
                    {
                        KeyManager[] keyManagers = super.getKeyManagers(keyStore);
                        for (int i = 0; i < keyManagers.length; i++)
                        {
                            keyManagers[i] = new ProxyAliasX509ExtendedKeyManager(keyManagers[i]);
                        }
                        return keyManagers;
                    }
                };
                // Disable TLS-level hostname verification for this test.
                clientTLS.setEndpointIdentificationAlgorithm(null);
                clientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/proxy_keystore.p12").getAbsolutePath());
                clientTLS.setKeyStorePassword("storepwd");
                clientTLS.setKeyStoreType("PKCS12");
                ClientConnector connector = new ClientConnector();
                connector.setSelectors(1);
                connector.setSslContextFactory(clientTLS);
                return new HttpClient(new HttpClientTransportDynamic(connector));
            }
        });
        startClient();

        testRequestsFromRemoteClients();
    }

    @Test
    public void testClientAuthProxyingWithSSLSessionResumptionDisabled() throws Exception
    {
        // To user the same HttpClient and server hostName, we need to disable
        // SSLSession caching, which is only possible by creating SSLEngine
        // without peer host information.
        // This is more CPU intensive because TLS sessions can never be resumed.

        startServer();
        startProxy(new AsyncProxyServlet()
        {
            @Override
            protected Request newProxyRequest(HttpServletRequest request, String rewrittenTarget)
            {
                String user = retrieveUser(request);
                Request proxyRequest = super.newProxyRequest(request, rewrittenTarget);
                proxyRequest.scheme(HttpScheme.HTTPS.asString()).port(serverConnector.getLocalPort());
                // Tag the request.
                proxyRequest.tag(new AliasTLSTag(user));
                return proxyRequest;
            }

            @Override
            protected HttpClient newHttpClient()
            {
                SslContextFactory.Client clientTLS = new SslContextFactory.Client()
                {
                    @Override
                    public SSLEngine newSSLEngine(String host, int port)
                    {
                        // This disable TLS session resumption and requires
                        // endpointIdentificationAlgorithm=null because the TLS implementation
                        // does not have the peer host to verify the server certificate.
                        return newSSLEngine();
                    }

                    @Override
                    protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
                    {
                        KeyManager[] keyManagers = super.getKeyManagers(keyStore);
                        for (int i = 0; i < keyManagers.length; i++)
                        {
                            keyManagers[i] = new ProxyAliasX509ExtendedKeyManager(keyManagers[i]);
                        }
                        return keyManagers;
                    }
                };
                // Disable hostname verification is required.
                clientTLS.setEndpointIdentificationAlgorithm(null);
                clientTLS.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/proxy_keystore.p12").getAbsolutePath());
                clientTLS.setKeyStorePassword("storepwd");
                clientTLS.setKeyStoreType("PKCS12");
                ClientConnector connector = new ClientConnector();
                connector.setSelectors(1);
                connector.setSslContextFactory(clientTLS);
                return new HttpClient(new HttpClientTransportDynamic(connector));
            }
        });
        startClient();

        testRequestsFromRemoteClients();
    }

    @Test
    public void testClientAuthProxyingWithCompositeSslContextFactory() throws Exception
    {
        // The idea here is to have a composite SslContextFactory that holds one for each user.
        // It requires a change in SslClientConnectionFactory to "sniff" for the composite.

        startServer();
        startProxy(new AsyncProxyServlet()
        {
            @Override
            protected Request newProxyRequest(HttpServletRequest request, String rewrittenTarget)
            {
                String user = retrieveUser(request);
                Request proxyRequest = super.newProxyRequest(request, rewrittenTarget);
                proxyRequest.scheme(HttpScheme.HTTPS.asString()).port(serverConnector.getLocalPort());
                proxyRequest.tag(user);
                return proxyRequest;
            }

            @Override
            protected HttpClient newHttpClient()
            {
                ProxyAliasClientSslContextFactory clientTLS = configure(new ProxyAliasClientSslContextFactory(), null);
                // Statically add SslContextFactory.Client instances per each user.
                clientTLS.factories.put("user1", configure(new SslContextFactory.Client(), "user1"));
                clientTLS.factories.put("user2", configure(new SslContextFactory.Client(), "user2"));
                ClientConnector connector = new ClientConnector();
                connector.setSelectors(1);
                connector.setSslContextFactory(clientTLS);
                return new HttpClient(new HttpClientTransportDynamic(connector));
            }

            private <T extends SslContextFactory.Client> T configure(T tls, String user)
            {
                // Disable TLS-level hostname verification for this test.
                tls.setEndpointIdentificationAlgorithm(null);
                tls.setKeyStorePath(MavenTestingUtils.getTestResourceFile("client_auth/proxy_keystore.p12").getAbsolutePath());
                tls.setKeyStorePassword("storepwd");
                tls.setKeyStoreType("PKCS12");
                if (user != null)
                {
                    tls.setCertAlias(user + "_proxy");
                    LifeCycle.start(tls);
                }
                return tls;
            }
        });
        startClient();

        testRequestsFromRemoteClients();
    }

    private void testRequestsFromRemoteClients() throws Exception
    {
        // User1 makes a request to the proxy using its own certificate.
        SslContextFactory clientTLS = client.getSslContextFactory();
        clientTLS.reload(ssl -> ssl.setCertAlias("user1_client"));
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .tag("user1")
            .send();
        Assertions.assertEquals(HttpStatus.OK_200, response.getStatus());
        String[] parts = response.getContentAsString().split("\n");
        String proxyClientSubject1 = parts[0];
        String proxyClientPort1 = parts[1];

        // User2 makes a request to the proxy using its own certificate.
        clientTLS.reload(ssl -> ssl.setCertAlias("user2_client"));
        response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .tag("user2")
            .send();
        Assertions.assertEquals(HttpStatus.OK_200, response.getStatus());
        parts = response.getContentAsString().split("\n");
        String proxyClientSubject2 = parts[0];
        String proxyClientPort2 = parts[1];

        Assertions.assertNotEquals(proxyClientSubject1, proxyClientSubject2);
        Assertions.assertNotEquals(proxyClientPort1, proxyClientPort2);
    }

    private static class AliasTLSTag implements ClientConnectionFactory.Decorator
    {
        private final String user;

        private AliasTLSTag(String user)
        {
            this.user = user;
        }

        @Override
        public ClientConnectionFactory apply(ClientConnectionFactory factory)
        {
            return (endPoint, context) ->
            {
                Connection connection = factory.newConnection(endPoint, context);
                SSLEngine sslEngine = (SSLEngine)context.get(SslClientConnectionFactory.SSL_ENGINE_CONTEXT_KEY);
                sslEngine.getSession().putValue("user", user);
                return connection;
            };
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            AliasTLSTag that = (AliasTLSTag)obj;
            return user.equals(that.user);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(user);
        }
    }

    private static class ProxyAliasX509ExtendedKeyManager extends SslContextFactory.X509ExtendedKeyManagerWrapper
    {
        private ProxyAliasX509ExtendedKeyManager(KeyManager keyManager)
        {
            super((X509ExtendedKeyManager)keyManager);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine)
        {
            for (String keyType : keyTypes)
            {
                String[] aliases = getClientAliases(keyType, issuers);
                if (aliases != null)
                {
                    SSLSession sslSession = engine.getSession();
                    String user = (String)sslSession.getValue("user");
                    String alias = user + "_proxy";
                    if (Arrays.asList(aliases).contains(alias))
                        return alias;
                }
            }
            return super.chooseEngineClientAlias(keyTypes, issuers, engine);
        }
    }

    private static class ProxyAliasClientSslContextFactory extends SslContextFactory.Client implements SslClientConnectionFactory.SslEngineFactory
    {
        private final Map<String, SslContextFactory.Client> factories = new ConcurrentHashMap<>();

        @Override
        public SSLEngine newSslEngine(String host, int port, Map<String, Object> context)
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            String user = (String)destination.getOrigin().getTag();
            return factories.compute(user, (key, value) -> value != null ? value : this).newSSLEngine(host, port);
        }
    }
}
