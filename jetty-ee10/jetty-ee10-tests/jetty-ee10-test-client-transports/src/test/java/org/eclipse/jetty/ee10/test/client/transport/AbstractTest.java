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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class AbstractTest
{
    public WorkDir workDir;

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected Path pemDir;
    protected SslContextFactory.Server sslContextFactoryServer;
    protected ServerQuicConfiguration serverQuicConfig;
    protected Server server;
    protected AbstractConnector connector;
    protected ServletContextHandler servletContextHandler;
    protected HttpClient client;

    private HTTP2Client http2Client;
    private final Map<Integer, org.eclipse.jetty.http2.api.Stream> h2Streams = new HashMap<>();
    private HTTP3Client http3Client;
    private final Map<Long, org.eclipse.jetty.http3.api.Stream> h3Streams = new HashMap<>();

    public static Collection<Transport> transports()
    {
        EnumSet<Transport> transports = EnumSet.allOf(Transport.class);
        if ("ci".equals(System.getProperty("env")))
            transports.remove(Transport.H3);
        return transports;
    }

    public static Collection<Transport> transportsNoFCGI()
    {
        Collection<Transport> transports = transports();
        transports.remove(Transport.FCGI);
        return transports;
    }

    public static Collection<Transport> transportsWithPushSupport()
    {
        Collection<Transport> transports = transports();
        transports.retainAll(List.of(Transport.H2C, Transport.H2));
        return transports;
    }

    public static Collection<Transport> transportsSecure()
    {
        EnumSet<Transport> transports = EnumSet.of(Transport.HTTPS, Transport.H2, Transport.H3);
        if ("ci".equals(System.getProperty("env")))
            transports.remove(Transport.H3);
        return transports;
    }

    public static Collection<Transport> transportsWithStreams()
    {
        EnumSet<Transport> transports = EnumSet.of(Transport.H2C, Transport.H3);
        if ("ci".equals(System.getProperty("env")))
            transports.remove(Transport.H3);
        return transports;
    }

    @BeforeEach
    public void prepare()
    {
        pemDir = workDir.getEmptyPathDir();
    }

    @AfterEach
    public void dispose()
    {
        h2Streams.clear();
        h3Streams.clear();
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    protected void start(Transport transport, HttpServlet servlet) throws Exception
    {
        startServer(transport, servlet);
        startClient(transport);
    }

    protected void startServer(Transport transport, HttpServlet servlet) throws Exception
    {
        prepareServer(transport, servlet);
        server.start();
    }

    protected void prepareServer(Transport transport, HttpServlet servlet) throws Exception
    {
        sslContextFactoryServer = newSslContextFactoryServer();
        Path serverPemDirectory = Files.createDirectories(pemDir.resolve("server"));
        serverQuicConfig = new ServerQuicConfiguration(sslContextFactoryServer, serverPemDirectory);
        if (server == null)
            server = newServer();
        connector = newConnector(transport, server);
        server.addConnector(connector);
        servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        servletContextHandler.addServlet(holder, "/*");
        server.setHandler(servletContextHandler);
    }

    protected Server newServer()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        return new Server(serverThreads);
    }

    protected SslContextFactory.Server newSslContextFactoryServer() throws Exception
    {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        configureSslContextFactory(ssl);
        return ssl;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory) throws Exception
    {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Path.of("src/test/resources/keystore.p12")))
        {
            keystore.load(is, "storepwd".toCharArray());
        }
        sslContextFactory.setTrustStore(keystore);
        sslContextFactory.setKeyStore(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    }

    protected void startClient(Transport transport) throws Exception
    {
        startClient(transport, null);
    }

    protected void startClient(Transport transport, Consumer<HttpClient> consumer) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(newHttpClientTransport(transport));
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        if (consumer != null)
            consumer.accept(client);
        client.start();
    }

    protected long newRequestOnStream(Transport transport) throws Exception
    {
        switch (transport)
        {
            case H2C, H2 ->
            {
                return sendHeadersWithNewH2Stream();
            }
            case H3 ->
            {
                return sendHeadersWithNewH3Stream();
            }
            default -> throw new IllegalArgumentException("Transport does not support streams: " + transport);
        }
    }

    private int sendHeadersWithNewH2Stream() throws Exception
    {
        org.eclipse.jetty.http2.api.Session session = newHttp2ClientSession(new org.eclipse.jetty.http2.api.Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        FuturePromise<org.eclipse.jetty.http2.api.Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, null);
        org.eclipse.jetty.http2.api.Stream stream = promise.get(5, TimeUnit.SECONDS);
        int streamId = stream.getId();
        h2Streams.put(streamId, stream);
        return streamId;
    }

    private long sendHeadersWithNewH3Stream() throws Exception
    {
        org.eclipse.jetty.http3.api.Session.Client session = newHttp3ClientSession(new org.eclipse.jetty.http3.api.Session.Client.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        CompletableFuture<org.eclipse.jetty.http3.api.Stream> cf = session.newRequest(new org.eclipse.jetty.http3.frames.HeadersFrame(metaData, false), null);
        org.eclipse.jetty.http3.api.Stream stream = cf.get(5, TimeUnit.SECONDS);
        long streamId = stream.getId();
        h3Streams.put(streamId, stream);
        return streamId;
    }

    protected void resetStream(Transport transport, long streamId)
    {
        switch (transport)
        {
            case H2C, H2 -> resetH2Stream((int)streamId);
            case H3 -> resetH3Stream(streamId);
            default -> throw new IllegalArgumentException("Transport does not support streams: " + transport);
        }
    }

    private void resetH2Stream(int streamId)
    {
        org.eclipse.jetty.http2.api.Stream stream = h2Streams.get(streamId);
        stream.reset(new ResetFrame(streamId, ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
    }

    private void resetH3Stream(long streamId)
    {
        org.eclipse.jetty.http3.api.Stream stream = h3Streams.get(streamId);
        stream.reset(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new Exception(getClass().getSimpleName() + " reset"));
    }

    private org.eclipse.jetty.http2.api.Session newHttp2ClientSession(org.eclipse.jetty.http2.api.Session.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = ((NetworkConnector)connector).getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<org.eclipse.jetty.http2.api.Session> promise = new FuturePromise<>();
        http2Client.connect(address, listener, promise);
        return promise.get(5, TimeUnit.SECONDS);
    }

    private org.eclipse.jetty.http3.api.Session.Client newHttp3ClientSession(org.eclipse.jetty.http3.api.Session.Client.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = ((NetworkConnector)connector).getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        CompletableFuture<org.eclipse.jetty.http3.api.Session.Client> cf = http3Client.connect(address, listener);
        return cf.get(5, TimeUnit.SECONDS);
    }

    protected MetaData.Request newRequest(String method, HttpFields fields)
    {
        return newRequest(method, "/", fields);
    }

    protected MetaData.Request newRequest(String method, String path, HttpFields fields)
    {
        String host = "localhost";
        int port = ((NetworkConnector)connector).getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP.asString(), new HostPortHttpField(authority), path, HttpVersion.HTTP_2, fields, -1);
    }

    public AbstractConnector newConnector(Transport transport, Server server)
    {
        return switch (transport)
        {
            case HTTP, HTTPS, H2C, H2, FCGI ->
                new ServerConnector(server, 1, 1, newServerConnectionFactory(transport));
            case H3 ->
                new QuicServerConnector(server, serverQuicConfig, newServerConnectionFactory(transport));
        };
    }

    protected ConnectionFactory[] newServerConnectionFactory(Transport transport)
    {
        List<ConnectionFactory> list = switch (transport)
        {
            case HTTP ->
                List.of(new HttpConnectionFactory(httpConfig));
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
                yield List.of(new HTTP3ServerConnectionFactory(serverQuicConfig, httpConfig));
            }
            case FCGI -> List.of(new ServerFCGIConnectionFactory(httpConfig));
        };
        return list.toArray(ConnectionFactory[]::new);
    }

    protected SslContextFactory.Client newSslContextFactoryClient() throws Exception
    {
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        configureSslContextFactory(ssl);
        ssl.setEndpointIdentificationAlgorithm(null);
        return ssl;
    }

    protected HttpClientTransport newHttpClientTransport(Transport transport) throws Exception
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
                http2Client = new HTTP2Client(clientConnector);
                yield new HttpClientTransportOverHTTP2(http2Client);
            }
            case H3 ->
            {
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSelectors(1);
                SslContextFactory.Client sslContextFactory = newSslContextFactoryClient();
                clientConnector.setSslContextFactory(sslContextFactory);
                Path clientPemDirectory = Files.createDirectories(pemDir.resolve("client"));
                http3Client = new HTTP3Client(new ClientQuicConfiguration(sslContextFactory, clientPemDirectory));
                yield new HttpClientTransportOverHTTP3(http3Client);
            }
            case FCGI -> new HttpClientTransportOverFCGI(1, "");
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

    public enum Transport
    {
        HTTP, HTTPS, H2C, H2, H3, FCGI;

        public boolean isSecure()
        {
            return switch (this)
            {
                case HTTP, H2C, FCGI -> false;
                case HTTPS, H2, H3 -> true;
            };
        }

        public boolean isMultiplexed()
        {
            return switch (this)
            {
                case HTTP, HTTPS, FCGI -> false;
                case H2C, H2, H3 -> true;
            };
        }
    }
}
