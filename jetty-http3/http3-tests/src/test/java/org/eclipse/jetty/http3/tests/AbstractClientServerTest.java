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

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.server.QuicServerConnector;
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
    protected QuicServerConnector connector;
    protected HTTP3Client client;
    protected Server server;

    protected void start(Session.Server.Listener listener) throws Exception
    {
        startServer(listener);
        startClient();
    }

    protected void startServer(Session.Server.Listener listener) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new QuicServerConnector(server, sslContextFactory, new RawHTTP3ServerConnectionFactory(listener));
        server.addConnector(connector);
        server.start();
    }

    protected void startClient() throws Exception
    {
        client = new HTTP3Client();
        client.start();
    }

    protected Session.Client newSession(Session.Client.Listener listener) throws Exception
    {
        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        return client.connect(address, listener).get(5, TimeUnit.SECONDS);
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
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }
}
