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
import java.util.concurrent.Executor;

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
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractTest
{
    @Parameterized.Parameters(name = "transport: {0}")
    public static List<Object[]> parameters() throws Exception
    {
        return Arrays.asList(new Object[]{Transport.HTTP}, new Object[]{Transport.HTTP2});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();

    private final Transport transport;
    protected Server server;
    protected ServerConnector connector;
    protected HttpClient client;

    public AbstractTest(Transport transport)
    {
        this.transport = transport;
    }

    public void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, provideServerConnectionFactory(transport));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(provideClientTransport(transport, clientThreads), null);
        client.setExecutor(clientThreads);
        client.start();
    }

    private ConnectionFactory provideServerConnectionFactory(Transport transport)
    {
        switch (transport)
        {
            case HTTP:
                return new HttpConnectionFactory(new HttpConfiguration());
            case HTTP2:
                return new HTTP2ServerConnectionFactory(new HttpConfiguration());
            default:
                throw new IllegalArgumentException();
        }
    }

    private HttpClientTransport provideClientTransport(Transport transport, Executor clientThreads)
    {
        switch (transport)
        {
            case HTTP:
            {
                return new HttpClientTransportOverHTTP(1);
            }
            case HTTP2:
            {
                HTTP2Client http2Client = new HTTP2Client();
                http2Client.setExecutor(clientThreads);
                return new HttpClientTransportOverHTTP2(http2Client);
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
    }

    @After
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    protected enum Transport
    {
        HTTP, HTTP2
    }
}
