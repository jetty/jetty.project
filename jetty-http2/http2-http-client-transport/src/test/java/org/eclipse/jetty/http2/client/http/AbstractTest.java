//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client.http;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

public class AbstractTest
{
    protected Server server;
    protected ServerConnector connector;
    protected HTTP2Client http2Client;
    protected HttpClient client;

    protected void start(ServerSessionListener listener) throws Exception
    {
        prepareServer(new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener));
        server.start();
        prepareClient();
        client.start();
    }

    protected void start(Handler handler) throws Exception
    {
        prepareServer(new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        server.setHandler(handler);
        server.start();
        prepareClient();
        client.start();
    }

    protected void prepareServer(ConnectionFactory connectionFactory)
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, 1, 1, connectionFactory);
        server.addConnector(connector);
    }

    protected void prepareClient()
    {
        http2Client = new HTTP2Client();
        client = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), null);
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        this.client.setExecutor(clientExecutor);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }
}
