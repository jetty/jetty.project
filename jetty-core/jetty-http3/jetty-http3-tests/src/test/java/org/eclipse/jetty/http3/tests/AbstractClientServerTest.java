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

package org.eclipse.jetty.http3.tests;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.QuicheTransport;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class AbstractClientServerTest
{
    public static List<TransportType> transports()
    {
        return List.of(TransportType.values());
    }

    public WorkDir workDir;

    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
        System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected Server server;
    protected SslContextFactory.Server serverSslContextFactory;
    protected AbstractNetworkConnector connector;
    protected HTTP3Client http3Client;
    protected HttpClient httpClient;
    protected Transport transport;

    protected void start(TransportType transportType, Handler handler) throws Exception
    {
        prepareServer(transportType, new HTTP3ServerConnectionFactory());
        server.setHandler(handler);
        server.start();
        startClient(transportType);
    }

    protected void start(TransportType transportType, Session.Server.Listener listener) throws Exception
    {
        startServer(transportType, listener);
        startClient(transportType);
    }

    protected void startServer(TransportType transportType, Session.Server.Listener listener) throws Exception
    {
        prepareServer(transportType, new RawHTTP3ServerConnectionFactory(listener));
        server.start();
    }

    private void prepareServer(TransportType transportType, ConnectionFactory serverConnectionFactory)
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(serverThreads, null, byteBufferPool);

        serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        serverSslContextFactory.setKeyStorePassword("storepwd");

        connector = switch (transportType)
        {
            case H3_QUICHE ->
            {
                QuicheServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(workDir.getEmptyPathDir()));
                yield new QuicheServerConnector(server, serverSslContextFactory, serverQuicConfig, serverConnectionFactory);
            }
        };
        server.addConnector(connector);

        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
    }

    protected void startClient(TransportType transportType) throws Exception
    {
        prepareClient(transportType, true);
        httpClient.start();
    }

    protected void prepareClient(TransportType transportType, boolean dynamic)
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool.Tracking();
        clientConnector.setByteBufferPool(byteBufferPool);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));

        ClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(switch (transportType)
        {
            case H3_QUICHE -> new QuicheClientQuicConfiguration();
        });

        http3Client = new HTTP3Client(clientQuicConfig, clientConnector);

        transport = switch (transportType)
        {
            case H3_QUICHE -> new QuicheTransport((QuicheClientQuicConfiguration)http3Client.getClientQuicConfiguration());
        };

        HttpClientTransport httpClientTransport = dynamic
            ? new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client, transport))
            : new HttpClientTransportOverHTTP3(http3Client, transport);
        httpClient = new HttpClient(httpClientTransport);
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        httpClient.addBean(mbeanContainer);
    }

    protected Session.Client newSession(Session.Client.Listener listener) throws Exception
    {
        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        return Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> http3Client.connect(transport, address, listener, p));
    }

    protected MetaData.Request newRequest(String path)
    {
        return newRequest(HttpMethod.GET, path);
    }

    protected MetaData.Request newRequest(HttpMethod method, String path)
    {
        return newRequest(method, path, HttpFields.EMPTY);
    }

    protected MetaData.Request newRequest(HttpMethod method, String path, HttpFields fields)
    {
        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort() + (path == null ? "/" : path));
        return new MetaData.Request(method.asString(), uri, HttpVersion.HTTP_3, fields);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(http3Client);
        LifeCycle.stop(httpClient);

        if (http3Client != null)
        {
            ArrayByteBufferPool.Tracking tracking = (ArrayByteBufferPool.Tracking)http3Client.getClientConnector().getByteBufferPool();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("client leaks: " + tracking.dumpLeaks(), tracking.getLeaks().size(), is(0)));
        }

        if (server != null)
        {
            ArrayByteBufferPool.Tracking tracking = (ArrayByteBufferPool.Tracking)server.getByteBufferPool();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("server leaks: " + tracking.dumpLeaks(), tracking.getLeaks().size(), is(0)));
        }

        LifeCycle.stop(server);
    }

    public enum TransportType
    {
        H3_QUICHE
    }
}
