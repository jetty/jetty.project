//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.Arrays;
import java.util.Collection;

import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractHttpClientServerTest
{
    @Parameterized.Parameters
    public static Collection<SslContextFactory[]> parameters()
    {
        return Arrays.asList(new SslContextFactory[]{null}, new SslContextFactory[]{new SslContextFactory()});
    }

    @Rule
    public final TestTracker tracker = new TestTracker();

    protected SslContextFactory sslContextFactory;
    protected String scheme;
    protected Server server;
    protected HttpClient client;
    protected ServerConnector connector;

    public AbstractHttpClientServerTest(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        this.scheme = (sslContextFactory == null ? HttpScheme.HTTP : HttpScheme.HTTPS).asString();
    }

    public void start(Handler handler) throws Exception
    {
        startServer(handler);
        startClient();
    }

    protected void startServer(Handler handler) throws Exception
    {
        if (sslContextFactory != null)
        {
            sslContextFactory.setEndpointIdentificationAlgorithm("");
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
            sslContextFactory.setTrustStorePassword("storepwd");
        }

        if (server == null)
        {
            QueuedThreadPool serverThreads = new QueuedThreadPool();
            serverThreads.setName("server");
            server = new Server(serverThreads);
        }
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    protected void startClient() throws Exception
    {
        startClient(new HttpClientTransportOverHTTP(1));
    }

    protected void startClient(HttpClientTransport transport) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(transport, sslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
        server = null;
    }
}
