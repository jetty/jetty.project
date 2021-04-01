//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.client;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connectable;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HttpClientConnectableTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Transport transport, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        switch (transport)
        {
            case HTTP:
            {
                connector = new ServerConnector(server, new HttpConnectionFactory());
                break;
            }
            case HTTPS:
            {
                HttpConfiguration httpsConfig = new HttpConfiguration();
                httpsConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory https = new HttpConnectionFactory(httpsConfig);
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
                sslContextFactory.setKeyStorePassword("storepwd");
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, https.getProtocol());
                connector = new ServerConnector(server, ssl, https);
                break;
            }
            case FCGI:
            {
                connector = new ServerConnector(server, new ServerFCGIConnectionFactory(new HttpConfiguration()));
                break;
            }
            case H2C:
            {
                connector = new ServerConnector(server, new HTTP2CServerConnectionFactory(new HttpConfiguration()));
                break;
            }
            case H2:
            {
                HttpConfiguration httpsConfig = new HttpConfiguration();
                httpsConfig.addCustomizer(new SecureRequestCustomizer());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
                sslContextFactory.setKeyStorePassword("storepwd");
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
                connector = new ServerConnector(server, ssl, alpn, h2);
                break;
            }
            default:
            {
                throw new TestAbortedException("Unsupported transport " + transport);
            }
        }

        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void startClient(Transport transport, MatchingConnectable connectable) throws Exception
    {
        ClientConnector connector = new ClientConnector();
        connectable.addBean(connector);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        connector.setExecutor(clientThreads);

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setTrustStorePassword("storepwd");
        connector.setSslContextFactory(sslContextFactory);

        HttpClientTransport clientTransport;
        switch (transport)
        {
            case HTTP:
            case HTTPS:
            {
                clientTransport = new HttpClientTransportOverHTTP(connectable);
                break;
            }
            case H2C:
            case H2:
            {
                clientTransport = new HttpClientTransportOverHTTP2(new HTTP2Client(connectable));
                break;
            }
            case FCGI:
            {
                clientTransport = new HttpClientTransportOverFCGI(connectable, "");
                break;
            }
            default:
            {
                throw new TestAbortedException("Unsupported transport " + transport);
            }
        }

        client = new HttpClient(clientTransport);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    public void testMatchingConnectable(Transport transport) throws Exception
    {
        startServer(transport, new EmptyServerHandler());

        MatchingConnectable matcher = new MatchingConnectable();
        startClient(transport, matcher);
        ClientConnector clientConnector = matcher.getBean(ClientConnector.class);
        assertNotNull(clientConnector);

        String scheme = transport.isTlsBased() ? "https" : "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        URI uri = URI.create(scheme + "://" + host + ":" + port + "/path");

        // No matching rules added, request should fail.
        ExecutionException exception = assertThrows(ExecutionException.class, () -> client.GET(uri));
        assertThat(exception.getCause(), Matchers.instanceOf(ConnectException.class));

        // Add a matching rule, request should succeed.
        matcher.rules.add((a, c) ->
        {
            clientConnector.connect(a, c);
            return true;
        });
        ContentResponse response = client.GET(uri);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        matcher.rules.clear();

        URI otherURI = URI.create(scheme + "://" + host + (port + 1) + "/path");
        // Add a rule that connects to another address.
        matcher.rules.add((a, c) ->
        {
            if (a instanceof InetSocketAddress)
            {
                assertEquals(otherURI.getPort(), ((InetSocketAddress)a).getPort());
                clientConnector.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), c);
                return true;
            }
            return false;
        });
        response = client.GET(uri);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    private static class MatchingConnectable extends ContainerLifeCycle implements Connectable
    {
        private final List<BiPredicate<SocketAddress, Map<String, Object>>> rules = new ArrayList<>();

        @Override
        public void connect(SocketAddress address, Map<String, Object> context)
        {
            if (rules.stream().noneMatch(rule -> rule.test(address, context)))
            {
                @SuppressWarnings("unchecked")
                Promise<Connection> promise = (Promise<Connection>)context.get(Connectable.CONNECTION_PROMISE_CONTEXT_KEY);
                promise.failed(new ConnectException());
            }
        }
    }
}
