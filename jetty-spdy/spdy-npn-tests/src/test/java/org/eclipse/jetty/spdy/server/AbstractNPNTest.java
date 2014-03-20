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

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;

public class AbstractNPNTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();
    protected Server server;
    protected SPDYServerConnector connector;
    protected SPDYClient.Factory clientFactory;

    protected InetSocketAddress prepare() throws Exception
    {
        server = new Server();
        connector = new SPDYServerConnector(server, newSslContextFactory(), null);
        connector.setPort(0);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);
        server.start();

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName(threadPool.getName() + "-client");
        clientFactory = new SPDYClient.Factory(threadPool);
        clientFactory.start();

        NextProtoNego.debug = true;

        return new InetSocketAddress("localhost", connector.getLocalPort());
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
    public void dispose() throws Exception
    {
        clientFactory.stop();
        server.stop();
    }
}
