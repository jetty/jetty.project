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

package org.eclipse.jetty.spdy.server.http;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executor;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractHTTPSPDYTest
{
    @Parameterized.Parameters
    public static Collection<Short[]> parameters()
    {
        return Arrays.asList(new Short[]{SPDY.V2}, new Short[]{SPDY.V3});
    }

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

    protected final short version;
    protected Server server;
    protected SPDYClient.Factory clientFactory;
    protected HTTPSPDYServerConnector connector;

    protected AbstractHTTPSPDYTest(short version)
    {
        this.version = version;
    }

    protected InetSocketAddress startHTTPServer(short version, Handler handler, long idleTimeout) throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool(256);
        threadPool.setName("serverQTP");
        server = new Server(threadPool);
        connector = newHTTPSPDYServerConnector(version);
        connector.setPort(0);
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected Server getServer()
    {
        return server;
    }

    protected HTTPSPDYServerConnector newHTTPSPDYServerConnector(short version)
    {
        // For these tests, we need the connector to speak HTTP over SPDY even in non-SSL
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setSendServerVersion(true);
        httpConfiguration.setSendXPoweredBy(true);
        return new HTTPSPDYServerConnector(server, version, httpConfiguration, new PushStrategy.None());
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
        return clientFactory.newSPDYClient(version).connect(socketAddress, listener);
    }

    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        return new SPDYClient.Factory(threadPool, null, null, connector.getIdleTimeout());
    }

    @After
    public void destroy() throws Exception
    {
        ((StdErrLog)Log.getLogger(HTTPSPDYServerConnector.class)).setHideStacks(true);
        if (clientFactory != null)
        {
            clientFactory.stop();
        }
        if (server != null)
        {
            server.stop();
            server.join();
        }
        ((StdErrLog)Log.getLogger(HTTPSPDYServerConnector.class)).setHideStacks(false);
    }
}
