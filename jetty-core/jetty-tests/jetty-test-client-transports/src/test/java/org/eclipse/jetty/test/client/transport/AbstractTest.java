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

package org.eclipse.jetty.test.client.transport;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.AnnotatedElement;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.management.MBeanServer;

import org.awaitility.Awaitility;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
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
import org.eclipse.jetty.io.ArrayByteBufferPool;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AbstractTest
{
    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
        System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected SslContextFactory.Server sslContextFactoryServer;
    protected Server server;
    protected AbstractConnector connector;
    protected HttpClient client;
    protected Path unixDomainPath;
    protected ArrayByteBufferPool.Tracking serverBufferPool;
    protected ArrayByteBufferPool.Tracking clientBufferPool;

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

    public static Collection<Transport> transportsNoUnixDomain()
    {
        Collection<Transport> transports = transports();
        transports.remove(Transport.UNIX_DOMAIN);
        return List.copyOf(transports);
    }

    public static Collection<Transport> transportsTCP()
    {
        Collection<Transport> transports = transports();
        transports.remove(Transport.H3);
        transports.remove(Transport.UNIX_DOMAIN);
        return List.copyOf(transports);
    }

    @AfterEach
    public void dispose(TestInfo testInfo) throws Exception
    {
        try
        {
            if (serverBufferPool != null && !isLeakTrackingDisabled(testInfo, "server"))
                assertNoLeaks(serverBufferPool, testInfo, "server-", "\n---\nServer Leaks: " + serverBufferPool.dumpLeaks() + "---\n");
            if (clientBufferPool != null && !isLeakTrackingDisabled(testInfo, "client"))
                assertNoLeaks(clientBufferPool, testInfo, "client-", "\n---\nClient Leaks: " + clientBufferPool.dumpLeaks() + "---\n");
        }
        finally
        {
            LifeCycle.stop(client);
            LifeCycle.stop(server);
        }
    }

    private void assertNoLeaks(ArrayByteBufferPool.Tracking bufferPool, TestInfo testInfo, String prefix, String msg) throws Exception
    {
        try
        {
            Awaitility.await().atMost(3, TimeUnit.SECONDS).until(() -> bufferPool.getLeaks().size(), Matchers.is(0));
        }
        catch (Exception e)
        {
            String className = testInfo.getTestClass().orElseThrow().getName();
            dumpHeap(prefix + className + msg);
            fail(e.getMessage());
        }
    }

    private static boolean isLeakTrackingDisabled(TestInfo testInfo, String tagSubValue)
    {
        String disableLeakTrackingTagValue = "DisableLeakTracking";
        String[] split = testInfo.getDisplayName().replace(",", " ").split(" ");
        String transports = split.length > 1 ? split[1] : "";
        String[] transportNames = transports.split("\\|");

        boolean disabled = isAnnotatedWithTagValue(testInfo.getTestMethod().orElseThrow(), disableLeakTrackingTagValue) ||
            isAnnotatedWithTagValue(testInfo.getTestClass().orElseThrow(), disableLeakTrackingTagValue);
        if (disabled)
        {
            System.err.println("Not tracking leaks");
            return true;
        }

        for (String transportName : transportNames)
        {
            disabled = isAnnotatedWithTagValue(testInfo.getTestMethod().orElseThrow(), disableLeakTrackingTagValue + ":" + transportName) ||
                isAnnotatedWithTagValue(testInfo.getTestClass().orElseThrow(), disableLeakTrackingTagValue + ":" + transportName);
            if (disabled)
            {
                System.err.println("Not tracking leaks for transport " + transportName);
                return true;
            }
        }

        disabled = isAnnotatedWithTagValue(testInfo.getTestMethod().orElseThrow(), disableLeakTrackingTagValue + ":" + tagSubValue) ||
            isAnnotatedWithTagValue(testInfo.getTestClass().orElseThrow(), disableLeakTrackingTagValue + ":" + tagSubValue);
        if (disabled)
        {
            System.err.println("Not tracking leaks for " + tagSubValue);
            return true;
        }

        for (String transportName : transportNames)
        {
            disabled = isAnnotatedWithTagValue(testInfo.getTestMethod().orElseThrow(), disableLeakTrackingTagValue + ":" + tagSubValue + ":" + transportName) ||
                isAnnotatedWithTagValue(testInfo.getTestClass().orElseThrow(), disableLeakTrackingTagValue + ":" + tagSubValue + ":" + transportName);
            if (disabled)
            {
                System.err.println("Not tracking leaks for " + tagSubValue + " using transport " + transportName);
                return true;
            }
        }

        return disabled;
    }

    private static boolean isAnnotatedWithTagValue(AnnotatedElement annotatedElement, String tagValue)
    {
        Tags tags = annotatedElement.getAnnotation(Tags.class);
        if (tags != null)
        {
            for (Tag tag : tags.value())
            {
                if (tag != null && tagValue.equalsIgnoreCase(tag.value()))
                    return true;
            }
            return false;
        }
        else
        {
            Tag tag = annotatedElement.getAnnotation(Tag.class);
            return tag != null && tagValue.equalsIgnoreCase(tag.value());
        }
    }

    private static void dumpHeap(String testMethodName) throws Exception
    {
        Path targetDir = Path.of("target/leaks");
        if (Files.exists(targetDir))
        {
            try (Stream<Path> stream = Files.walk(targetDir))
            {
                stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
        Files.createDirectories(targetDir);
        String dumpName = targetDir.resolve(testMethodName + ".hprof").toString();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Class<?> mxBeanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Object mxBean = ManagementFactory.newPlatformMXBeanProxy(
            server, "com.sun.management:type=HotSpotDiagnostic", mxBeanClass);
        mxBeanClass.getMethod("dumpHeap", String.class, boolean.class).invoke(mxBean, dumpName, true);
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
        serverBufferPool = new ArrayByteBufferPool.Tracking();
        return new Server(serverThreads, null, serverBufferPool);
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
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(newHttpClientTransport(transport));
        clientBufferPool = new ArrayByteBufferPool.Tracking();
        client.setByteBufferPool(clientBufferPool);
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
                HTTP3ServerConnector h3Connector = new HTTP3ServerConnector(server, sslContextFactoryServer, newServerConnectionFactory(transport));
                h3Connector.getQuicConfiguration().setPemWorkDirectory(Path.of(System.getProperty("java.io.tmpdir")));
                yield h3Connector;
            case UNIX_DOMAIN:
                UnixDomainServerConnector unixConnector = new UnixDomainServerConnector(server, 1, 1, newServerConnectionFactory(transport));
                unixConnector.setUnixDomainPath(unixDomainPath);
                yield unixConnector;
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
                    HTTP2Client http2Client = new HTTP2Client(clientConnector);
                    yield new HttpClientTransportOverHTTP2(http2Client);
                }
                case H3 ->
                {
                    HTTP3Client http3Client = new HTTP3Client();
                    ClientConnector clientConnector = http3Client.getClientConnector();
                    clientConnector.setSelectors(1);
                    clientConnector.setSslContextFactory(newSslContextFactoryClient());
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
