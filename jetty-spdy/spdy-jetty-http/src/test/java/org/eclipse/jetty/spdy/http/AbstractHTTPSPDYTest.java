//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.http;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.SPDYClient;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

public abstract class AbstractHTTPSPDYTest
{
    @Rule
    public final TestWatchman testName = new TestWatchman()
    {
        @Override
        public void starting(FrameworkMethod method)
        {
            super.starting(method);
            System.err.printf("Running %s.%s()%n",
                    method.getMethod().getDeclaringClass().getName(),
                    method.getName());
        }
    };

    protected Server server;
    protected SPDYClient.Factory clientFactory;
    protected SPDYServerConnector connector;

    protected InetSocketAddress startHTTPServer(Handler handler) throws Exception
    {
        return startHTTPServer(SPDY.V2, handler);
    }

    protected InetSocketAddress startHTTPServer(short version, Handler handler) throws Exception
    {
        server = new Server();
        connector = newHTTPSPDYServerConnector(version);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SPDYServerConnector newHTTPSPDYServerConnector(short version)
    {
        // For these tests, we need the connector to speak HTTP over SPDY even in non-SSL
        SPDYServerConnector connector = new HTTPSPDYServerConnector();
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version, connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, new PushStrategy.None());
        connector.setDefaultAsyncConnectionFactory(defaultFactory);
        return connector;
    }

    protected Session startClient(InetSocketAddress socketAddress, SessionFrameListener listener) throws Exception
    {
        return startClient(SPDY.V2, socketAddress, listener);
    }

    protected Session startClient(short version, InetSocketAddress socketAddress, SessionFrameListener listener) throws Exception
    {
        if (clientFactory == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(threadPool.getName() + "-client");
            clientFactory = newSPDYClientFactory(threadPool);
            clientFactory.start();
        }
        return clientFactory.newSPDYClient(version).connect(socketAddress, listener).get(5, TimeUnit.SECONDS);
    }

    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        return new SPDYClient.Factory(threadPool);
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

    protected short version()
    {
        return SPDY.V2;
    }
}
