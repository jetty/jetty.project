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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.unixsocket.server.UnixSocketConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http.client.Transport.UNIX_SOCKET;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TransportScenario
{
    private static final Logger LOG = LoggerFactory.getLogger(TransportScenario.class);

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected final Transport transport;
    protected SslContextFactory.Server sslContextFactory;
    protected Server server;
    protected Connector connector;
    protected ServletContextHandler context;
    protected String servletPath = "/servlet";
    protected HttpClient client;
    protected Path sockFile;
    protected final BlockingQueue<String> requestLog = new BlockingArrayQueue<>();

    public TransportScenario(final Transport transport) throws IOException
    {
        this.transport = transport;

        String dir = System.getProperty("jetty.unixdomain.dir");
        assertNotNull(dir);
        sockFile = Files.createTempFile(Path.of(dir), "unix_", ".sock");
        assertTrue(sockFile.toAbsolutePath().toString().length() < UnixSocketConnector.MAX_UNIX_SOCKET_PATH_LENGTH, "Unix-Domain path too long");
        Files.delete(sockFile);

        // Disable UNIX_SOCKET due to jnr/jnr-unixsocket#69.
        Assumptions.assumeTrue(transport != UNIX_SOCKET);
    }

    public Optional<String> getNetworkConnectorLocalPort()
    {
        if (connector instanceof ServerConnector)
        {
            ServerConnector serverConnector = (ServerConnector)connector;
            return Optional.of(Integer.toString(serverConnector.getLocalPort()));
        }

        return Optional.empty();
    }

    public Optional<Integer> getNetworkConnectorLocalPortInt()
    {
        if (connector instanceof ServerConnector)
        {
            ServerConnector serverConnector = (ServerConnector)connector;
            return Optional.of(serverConnector.getLocalPort());
        }

        return Optional.empty();
    }

    public String getScheme()
    {
        return transport.isTlsBased() ? "https" : "http";
    }

    public HTTP2Client newHTTP2Client(SslContextFactory.Client sslContextFactory)
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(sslContextFactory);
        return new HTTP2Client(clientConnector);
    }

    public HttpClient newHttpClient(HttpClientTransport transport)
    {
        return new HttpClient(transport);
    }

    public Connector newServerConnector(Server server)
    {
        if (transport == Transport.UNIX_SOCKET)
        {
            UnixSocketConnector unixSocketConnector = new UnixSocketConnector(server, provideServerConnectionFactory(transport));
            unixSocketConnector.setUnixSocket(sockFile.toString());
            return unixSocketConnector;
        }
        return new ServerConnector(server, provideServerConnectionFactory(transport));
    }

    public String newURI()
    {
        StringBuilder ret = new StringBuilder();
        ret.append(getScheme());
        ret.append("://localhost");
        Optional<String> localPort = getNetworkConnectorLocalPort();
        localPort.ifPresent(s -> ret.append(':').append(s));
        return ret.toString();
    }

    public HttpClientTransport provideClientTransport(Transport transport, SslContextFactory.Client sslContextFactory)
    {
        switch (transport)
        {
            case HTTP:
            case HTTPS:
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                clientConnector.setSslContextFactory(sslContextFactory);
                return new HttpClientTransportOverHTTP(clientConnector);
            }
            case H2C:
            case H2:
            {
                HTTP2Client http2Client = newHTTP2Client(sslContextFactory);
                return new HttpClientTransportOverHTTP2(http2Client);
            }
            case FCGI:
            {
                return new HttpClientTransportOverFCGI(1, "");
            }
            case UNIX_SOCKET:
            {
                return new HttpClientTransportOverUnixSockets(sockFile.toString());
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
    }

    public ConnectionFactory[] provideServerConnectionFactory(Transport transport)
    {
        List<ConnectionFactory> result = new ArrayList<>();
        switch (transport)
        {
            case UNIX_SOCKET:
            case HTTP:
            {
                result.add(new HttpConnectionFactory(httpConfig));
                break;
            }
            case HTTPS:
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
                result.add(ssl);
                result.add(http);
                break;
            }
            case H2C:
            {
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                result.add(new HTTP2CServerConnectionFactory(httpConfig));
                break;
            }
            case H2:
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                httpConfig.addCustomizer(new HostHeaderCustomizer());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
                result.add(ssl);
                result.add(alpn);
                result.add(h2);
                break;
            }
            case FCGI:
            {
                result.add(new ServerFCGIConnectionFactory(httpConfig));
                break;
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
        return result.toArray(new ConnectionFactory[0]);
    }

    public void setConnectionIdleTimeout(long idleTimeout)
    {
        if (connector instanceof AbstractConnector)
            ((AbstractConnector)connector).setIdleTimeout(idleTimeout);
    }

    public void setServerIdleTimeout(long idleTimeout)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
            h2.setStreamIdleTimeout(idleTimeout);
        else
            setConnectionIdleTimeout(idleTimeout);
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
            h2.setMaxConcurrentStreams(maxRequestsPerConnection);
    }

    public void start(Handler handler) throws Exception
    {
        start(handler, null);
    }

    public void start(Handler handler, Consumer<HttpClient> config) throws Exception
    {
        startServer(handler);
        startClient(config);
    }

    public void start(HttpServlet servlet) throws Exception
    {
        startServer(servlet);
        startClient(null);
    }

    public void startClient() throws Exception
    {
        startClient(null);
    }

    public void startClient(Consumer<HttpClient> config) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientThreads.setDetailedDump(true);
        SslContextFactory.Client sslContextFactory = newClientSslContextFactory();
        client = newHttpClient(provideClientTransport(transport, sslContextFactory));
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());

        if (config != null)
            config.accept(client);

        client.start();
        if (server != null)
            server.addBean(client);
    }

    public void startServer(HttpServlet servlet) throws Exception
    {
        context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, servletPath);
        startServer(context);
    }

    public void startServer(Handler handler) throws Exception
    {
        sslContextFactory = newServerSslContextFactory();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
        connector = newServerConnector(server);
        server.addConnector(connector);

        server.setRequestLog((request, response) ->
        {
            int status = response.getCommittedMetaData().getStatus();
            requestLog.offer(String.format("%s %s %s %03d", request.getMethod(), request.getRequestURI(), request.getProtocol(), status));
        });

        server.setHandler(handler);

        try
        {
            server.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    protected SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    protected SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    }

    public void stopClient() throws Exception
    {
        if (client != null)
            client.stop();
    }

    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    public void stop()
    {
        try
        {
            stopClient();
        }
        catch (Exception x)
        {
            LOG.trace("IGNORED", x);
        }

        try
        {
            stopServer();
        }
        catch (Exception x)
        {
            LOG.trace("IGNORED", x);
        }

        if (sockFile != null)
        {
            try
            {
                Files.deleteIfExists(sockFile);
            }
            catch (IOException e)
            {
                LOG.warn("Unable to delete sockFile: {}", sockFile, e);
            }
        }
    }
}
