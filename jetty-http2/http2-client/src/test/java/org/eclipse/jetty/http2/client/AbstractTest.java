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

package org.eclipse.jetty.http2.client;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Rule;

public class AbstractTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    protected ServerConnector connector;
    protected String servletPath = "/test";
    protected HTTP2Client client;
    protected Server server;

    protected void start(HttpServlet servlet) throws Exception
    {
        prepareServer(new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        context.addServlet(new ServletHolder(servlet), servletPath + "/*");
        customizeContext(context);
        server.start();

        prepareClient();
        client.start();
    }

    protected void customizeContext(ServletContextHandler context)
    {
    }

    protected void start(ServerSessionListener listener) throws Exception
    {
        prepareServer(new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener));
        server.start();

        prepareClient();
        client.start();
    }

    protected void prepareServer(ConnectionFactory... connectionFactories)
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, 1, 1, connectionFactories);
        server.addConnector(connector);
    }

    protected void prepareClient()
    {
        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
    }

    protected Session newClient(Session.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, listener, promise);
        return promise.get(5, TimeUnit.SECONDS);
    }

    protected MetaData.Request newRequest(String method, HttpFields fields)
    {
        return newRequest(method, "", fields);
    }

    protected MetaData.Request newRequest(String method, String pathInfo, HttpFields fields)
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP, new HostPortHttpField(authority), servletPath + pathInfo, HttpVersion.HTTP_2, fields);
    }

    @After
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }
}
