//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.client.http;

import java.util.Arrays;
import java.util.Collection;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
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
    protected ServerConnector connector;
    protected SPDYClient.Factory factory;
    protected HttpClient client;

    public AbstractHttpClientServerTest(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        this.scheme = (sslContextFactory == null ? HttpScheme.HTTP : HttpScheme.HTTPS).asString();
    }

    public void start(Handler handler) throws Exception
    {
        short version = SPDY.V3;

        HTTPSPDYServerConnectionFactory httpSPDY = new HTTPSPDYServerConnectionFactory(version, new HttpConfiguration());
        if (sslContextFactory != null)
        {
            sslContextFactory.setEndpointIdentificationAlgorithm("");
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
            sslContextFactory.setTrustStorePassword("storepwd");
        }

        server = new Server();
        connector = new ServerConnector(server, AbstractConnectionFactory.getFactories(sslContextFactory, httpSPDY));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");

        factory = new SPDYClient.Factory(executor);
        factory.start();
        client = new HttpClient(new HttpClientTransportOverSPDY(factory.newSPDYClient(version)), sslContextFactory);
        client.setExecutor(executor);
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (factory != null)
            factory.stop();
        if (server != null)
            server.stop();
    }
}
