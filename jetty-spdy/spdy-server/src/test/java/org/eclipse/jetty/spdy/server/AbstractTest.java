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


package org.eclipse.jetty.spdy.server;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public abstract class AbstractTest
{
    @Rule
    public final TestWatcher testName = new TestWatcher()
    {

        @Override
        public void starting(Description description)
        {
            super.starting(description);
            System.err.printf("Running %s.%s()%n",
                    description.getClassName(),
                    description.getMethodName());
        }
    };

    protected final short version = SPDY.V2;

    protected Server server;
    protected SPDYClient.Factory clientFactory;
    protected ServerConnector connector;

    protected InetSocketAddress startServer(ServerSessionFrameListener listener) throws Exception
    {
        return startServer(version, listener);
    }

    protected InetSocketAddress startServer(short version, ServerSessionFrameListener listener) throws Exception
    {
        if (server == null)
            server = newServer();
        if (connector == null)
            connector = newSPDYServerConnector(server, listener);
        if (listener == null)
            listener = connector.getConnectionFactory(SPDYServerConnectionFactory.class).getServerSessionFrameListener();

        ConnectionFactory spdy = new SPDYServerConnectionFactory(version, listener);
        connector.addConnectionFactory(spdy);
        connector.setPort(0);
        server.addConnector(connector);

        if (connector.getConnectionFactory(NPNServerConnectionFactory.class)!=null)
            connector.getConnectionFactory(NPNServerConnectionFactory.class).setDefaultProtocol(spdy.getProtocol());
        else
            connector.setDefaultProtocol(spdy.getProtocol());

        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected Server newServer()
    {
        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName(pool.getName()+"-server");
        return new Server(pool);
    }

    protected ServerConnector newSPDYServerConnector(Server server, ServerSessionFrameListener listener)
    {
        return new SPDYServerConnector(server, listener);
    }

    protected Session startClient(InetSocketAddress socketAddress, SessionFrameListener listener) throws Exception
    {
        return startClient(version, socketAddress, listener);
    }

    protected Session startClient(short version, InetSocketAddress socketAddress, SessionFrameListener listener) throws Exception
    {
        if (clientFactory == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(threadPool.getName() + "-client");
            clientFactory = newSPDYClientFactory(threadPool);
        }
        clientFactory.start();

        return clientFactory.newSPDYClient(version).connect(socketAddress, listener);
    }

    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        return new SPDYClient.Factory(threadPool);
    }

    protected SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setProtocol("TLSv1");
        sslContextFactory.setIncludeProtocols("TLSv1");
        return sslContextFactory;
    }

    @After
    public void destroy() throws Exception
    {
        if (clientFactory != null)
        {
            clientFactory.stop();
        }
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }
}
