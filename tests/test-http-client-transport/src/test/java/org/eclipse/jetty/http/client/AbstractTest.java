//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http.client;

import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractTest
{
    private static final HTTP2Client http2Client;

    static
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("h2-client");
        http2Client = new HTTP2Client(clientThreads);
    }

    @Parameterized.Parameters(name = "{index}: mod:{0}")
    public static List<Object[]> parameters() throws Exception
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();

        return Arrays.asList(
                new Object[]{new HttpClientTransportOverHTTP(), new HttpConnectionFactory(httpConfiguration)},
                new Object[]{new HttpClientTransportOverHTTP2(http2Client), new HTTP2ServerConnectionFactory(httpConfiguration)}
        );
    }

    @BeforeClass
    public static void prepare() throws Exception
    {
        http2Client.start();
    }

    @AfterClass
    public static void dispose() throws Exception
    {
        http2Client.stop();
    }

    @Rule
    public final TestTracker tracker = new TestTracker();

    private final HttpClientTransport httpClientTransport;
    private final ConnectionFactory serverConnectionFactory;
    protected Server server;
    protected ServerConnector connector;
    protected HttpClient client;

    public AbstractTest(HttpClientTransport httpClientTransport, ConnectionFactory serverConnectionFactory)
    {
        this.httpClientTransport = httpClientTransport;
        this.serverConnectionFactory = serverConnectionFactory;
    }

    public void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, serverConnectionFactory);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(httpClientTransport, null);
        client.setExecutor(clientThreads);
        client.start();
    }

    @After
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }
}
