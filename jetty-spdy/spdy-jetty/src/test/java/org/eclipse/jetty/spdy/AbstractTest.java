/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

public abstract class AbstractTest
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

    protected InetSocketAddress startServer(ServerSessionFrameListener listener) throws Exception
    {
        if (connector == null)
            connector = newSPDYServerConnector(listener);
        connector.setPort(0);
        server = new Server();
        server.addConnector(connector);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SPDYServerConnector newSPDYServerConnector(ServerSessionFrameListener listener)
    {
        return new SPDYServerConnector(listener);
    }

    protected Session startClient(InetSocketAddress socketAddress, SessionFrameListener listener) throws Exception
    {
        if (clientFactory == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName(threadPool.getName() + "-client");
            clientFactory = newSPDYClientFactory(threadPool);
            clientFactory.start();
        }
        return clientFactory.newSPDYClient(SPDY.V2).connect(socketAddress, listener).get(5, TimeUnit.SECONDS);
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
        sslContextFactory.setTrustStore("src/test/resources/truststore.jks");
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
