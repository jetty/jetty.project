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

package org.eclipse.jetty.test.client.transport;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AbstractTest
{
    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected SslContextFactory.Server sslContextFactoryServer;
    protected Server server;
    protected AbstractConnector connector;
    protected HttpClient client;
    protected Path unixDomainPath;

    private static EnumSet<Transport> allTransports()
    {
        EnumSet<Transport> transports = EnumSet.allOf(Transport.class);
        // Disable H3 tests unless explicitly enabled with a system property.
        if (!Boolean.getBoolean("org.eclipse.jetty.test.client.transport.H3.enable"))
            transports.remove(Transport.H3);
        return transports;
    }

    public static List<Transport> transports()
    {
        return List.copyOf(allTransports());
    }

    public static List<Transport> transportsNoFCGI()
    {
        EnumSet<Transport> transports = allTransports();
        transports.remove(Transport.FCGI);
        return List.copyOf(transports);
    }

    public static List<Transport> transportsNoUnixDomain()
    {
        EnumSet<Transport> transports = allTransports();
        transports.remove(Transport.UNIX_DOMAIN);
        return List.copyOf(transports);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    protected void start(Transport transport, Handler handler) throws Exception
    {
        startServer(transport, handler);
        startClient(transport);
    }

    protected void startServer(Transport transport, Handler handler) throws Exception
    {
        prepareServer(transport, handler);
        server.start();
    }

    protected void prepareServer(Transport transport, Handler handler) throws Exception
    {
        if (transport == Transport.UNIX_DOMAIN)
        {
            String unixDomainDir = System.getProperty("jetty.unixdomain.dir", System.getProperty("java.io.tmpdir"));
            unixDomainPath = Files.createTempFile(Path.of(unixDomainDir), "unix_", ".sock");
            assertTrue(unixDomainPath.toAbsolutePath().toString().length() < UnixDomainServerConnector.MAX_UNIX_DOMAIN_PATH_LENGTH, "Unix-Domain path too long");
            Files.delete(unixDomainPath);
        }
        sslContextFactoryServer = newSslContextFactoryServer();
        if (server == null)
            server = newServer();
        connector = newConnector(transport, server);
        server.addConnector(connector);
        server.setHandler(handler);
    }

    protected Server newServer()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        return new Server(serverThreads);
    }

    protected SslContextFactory.Server newSslContextFactoryServer()
    {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath("src/test/resources/keystore.p12");
        ssl.setKeyStorePassword("storepwd");
        ssl.setUseCipherSuitesOrder(true);
        ssl.setCipherComparator(HTTP2Cipher.COMPARATOR);
        return ssl;
    }

    protected void startClient(Transport transport) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(newHttpClientTransport(transport));
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        client.start();
    }

    public AbstractConnector newConnector(Transport transport, Server server)
    {
        return switch (transport)
        {
            case HTTP:
            case HTTPS:
            case H2C:
            case H2:
            case FCGI:
                yield new ServerConnector(server, 1, 1, newServerConnectionFactory(transport));
            case H3:
                yield new HTTP3ServerConnector(server, sslContextFactoryServer, newServerConnectionFactory(transport));
            case UNIX_DOMAIN:
                UnixDomainServerConnector connector = new UnixDomainServerConnector(server, 1, 1, newServerConnectionFactory(transport));
                connector.setUnixDomainPath(unixDomainPath);
                yield connector;
        };
    }

    protected ConnectionFactory[] newServerConnectionFactory(Transport transport)
    {
        List<ConnectionFactory> list = switch (transport)
        {
            case HTTP, UNIX_DOMAIN -> List.of(new HttpConnectionFactory(httpConfig));
            case HTTPS ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactoryServer, http.getProtocol());
                yield List.of(ssl, http);
            }
            case H2C ->
            {
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                yield List.of(new HTTP2CServerConnectionFactory(httpConfig));
            }
            case H2 ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactoryServer, alpn.getProtocol());
                yield List.of(ssl, alpn, h2);
            }
            case H3 ->
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                yield List.of(new HTTP3ServerConnectionFactory(httpConfig));
            }
            case FCGI -> List.of(new ServerFCGIConnectionFactory(httpConfig));
        };
        return list.toArray(ConnectionFactory[]::new);
    }

    protected SslContextFactory.Client newSslContextFactoryClient()
    {
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        ssl.setKeyStorePath("src/test/resources/keystore.p12");
        ssl.setKeyStorePassword("storepwd");
        ssl.setEndpointIdentificationAlgorithm(null);
        return ssl;
    }

    protected HttpClientTransport newHttpClientTransport(Transport transport)
    {
        return switch (transport)
            {
                case HTTP, HTTPS ->
                {
                    ClientConnector clientConnector = new ClientConnector();
                    clientConnector.setSelectors(1);
                    clientConnector.setSslContextFactory(newSslContextFactoryClient());
                    yield new HttpClientTransportOverHTTP(clientConnector);
                }
                case H2C, H2 ->
                {
                    ClientConnector clientConnector = new ClientConnector();
                    clientConnector.setSelectors(1);
                    clientConnector.setSslContextFactory(newSslContextFactoryClient());
                    HTTP2Client http2Client = new HTTP2Client(clientConnector);
                    yield new HttpClientTransportOverHTTP2(http2Client);
                }
                case H3 ->
                {
                    HTTP3Client http3Client = new HTTP3Client();
                    ClientConnector clientConnector = http3Client.getClientConnector();
                    clientConnector.setSelectors(1);
                    clientConnector.setSslContextFactory(newSslContextFactoryClient());
                    http3Client.getQuicConfiguration().setVerifyPeerCertificates(false);
                    yield new HttpClientTransportOverHTTP3(http3Client);
                }
                case FCGI -> new HttpClientTransportOverFCGI(1, "");
                case UNIX_DOMAIN ->
                {
                    ClientConnector clientConnector = ClientConnector.forUnixDomain(unixDomainPath);
                    clientConnector.setSelectors(1);
                    clientConnector.setSslContextFactory(newSslContextFactoryClient());
                    yield new HttpClientTransportOverHTTP(clientConnector);
                }
            };
    }

    protected URI newURI(Transport transport)
    {
        String scheme = transport.isSecure() ? "https" : "http";
        String uri = scheme + "://localhost";
        if (connector instanceof NetworkConnector networkConnector)
            uri += ":" + networkConnector.getLocalPort();
        return URI.create(uri);
    }

    protected void setStreamIdleTimeout(long idleTimeout)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
        {
            h2.setStreamIdleTimeout(idleTimeout);
        }
        else
        {
            AbstractHTTP3ServerConnectionFactory h3 = connector.getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
            if (h3 != null)
                h3.getHTTP3Configuration().setStreamIdleTimeout(idleTimeout);
            else
                connector.setIdleTimeout(idleTimeout);
        }
    }

    protected void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
        {
            h2.setMaxConcurrentStreams(maxRequestsPerConnection);
        }
        else
        {
            if (connector instanceof QuicServerConnector)
                ((QuicServerConnector)connector).getQuicConfiguration().setMaxBidirectionalRemoteStreams(maxRequestsPerConnection);
        }
    }

    public enum Transport
    {
        HTTP, HTTPS, H2C, H2, H3, FCGI, UNIX_DOMAIN;

        public boolean isSecure()
        {
            return switch (this)
            {
                case HTTP, H2C, FCGI, UNIX_DOMAIN -> false;
                case HTTPS, H2, H3 -> true;
            };
        }

        public boolean isMultiplexed()
        {
            return switch (this)
            {
                case HTTP, HTTPS, FCGI, UNIX_DOMAIN -> false;
                case H2C, H2, H3 -> true;
            };
        }
    }
}
