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
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(WorkDirExtension.class)
public class AbstractClientServerTest
{
    public WorkDir workDir;

    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
        System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected Server server;
    protected QuicServerConnector connector;
    protected HTTP3Client http3Client;
    protected HttpClient httpClient;

    protected void start(Handler handler) throws Exception
    {
        ServerQuicConfiguration quicConfiguration = newServerQuicConfiguration(false);
        prepareServer(quicConfiguration, new HTTP3ServerConnectionFactory(quicConfiguration));
        server.setHandler(handler);
        server.start();
        startClient();
    }

    protected void start(Session.Server.Listener listener) throws Exception
    {
        startServer(false, listener);
        startClient();
    }

    protected void start(boolean needClientAuth, Session.Server.Listener listener) throws Exception
    {
        startServer(needClientAuth, listener);
        startClient();
    }

    private void startServer(boolean needClientAuth, Session.Server.Listener listener) throws Exception
    {
        ServerQuicConfiguration quicConfiguration = newServerQuicConfiguration(needClientAuth);
        prepareServer(quicConfiguration, new RawHTTP3ServerConnectionFactory(quicConfiguration, listener));
        server.start();
    }

    private ServerQuicConfiguration newServerQuicConfiguration(boolean needClientAuth)
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setNeedClientAuth(needClientAuth);
        sslServer.setKeyStorePath("src/test/resources/keystore.p12");
        sslServer.setKeyStorePassword("storepwd");
        return new ServerQuicConfiguration(sslServer, workDir.getEmptyPathDir());
    }

    private void prepareServer(ServerQuicConfiguration quicConfiguration, ConnectionFactory serverConnectionFactory)
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new QuicServerConnector(server, quicConfiguration, serverConnectionFactory);
        server.addConnector(connector);
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
    }

    protected void startClient() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        clientConnector.setSslContextFactory(sslClient);
        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslClient, null);
        http3Client = new HTTP3Client(quicConfiguration, clientConnector);
        httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client)));
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        httpClient.addBean(mbeanContainer);
        httpClient.start();
    }

    protected Session.Client newSession(Session.Client.Listener listener) throws Exception
    {
        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        return http3Client.connect(address, listener).get(30, TimeUnit.SECONDS);
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
        LifeCycle.stop(server);
    }
}
