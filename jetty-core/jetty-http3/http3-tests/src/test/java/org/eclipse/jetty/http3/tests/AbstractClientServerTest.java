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

package org.eclipse.jetty.http3.tests;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.http.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

public class AbstractClientServerTest
{
    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context ->
        System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected Server server;
    protected HTTP3ServerConnector connector;
    protected HTTP3Client http3Client;
    protected HttpClient httpClient;

    protected void start(Handler handler) throws Exception
    {
        prepareServer(new HTTP3ServerConnectionFactory());
        server.setHandler(handler);
        server.start();
        startClient();
    }

    protected void start(Session.Server.Listener listener) throws Exception
    {
        startServer(listener);
        startClient();
    }

    protected void startServer(Session.Server.Listener listener) throws Exception
    {
        prepareServer(new RawHTTP3ServerConnectionFactory(listener));
        server.start();
    }

    private void prepareServer(ConnectionFactory serverConnectionFactory)
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new HTTP3ServerConnector(server, sslContextFactory, serverConnectionFactory);
        server.addConnector(connector);
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
    }

    protected void startClient() throws Exception
    {
        http3Client = new HTTP3Client();
        http3Client.getQuicConfiguration().setVerifyPeerCertificates(false);
        httpClient = new HttpClient(new HttpClientTransportDynamic(new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client)));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient.setExecutor(clientThreads);
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanContainer = new MBeanContainer(mbeanServer);
        httpClient.addBean(mbeanContainer);
        httpClient.start();
    }

    protected Session.Client newSession(Session.Client.Listener listener) throws Exception
    {
        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        return http3Client.connect(address, listener).get(5, TimeUnit.SECONDS);
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
