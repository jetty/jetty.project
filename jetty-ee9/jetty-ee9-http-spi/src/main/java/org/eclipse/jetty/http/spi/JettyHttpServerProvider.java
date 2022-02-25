//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Jetty implementation of <a href="http://java.sun.com/javase/6/docs/jre/api/net/httpserver/spec/index.html">Java HTTP Server SPI</a>
 */
public class JettyHttpServerProvider extends HttpServerProvider
{

    private static Server _server;

    public static void setServer(Server server)
    {
        _server = server;
    }

    @Override
    public HttpServer createHttpServer(InetSocketAddress addr, int backlog)
        throws IOException
    {
        Server server = _server;
        boolean shared = true;

        if (server == null)
        {
            ThreadPool threadPool = new DelegatingThreadPool(new QueuedThreadPool());
            server = new Server(threadPool);

            HandlerList handlerCollection = new HandlerList(new ContextHandlerCollection(), new DefaultHandler());
            server.setHandler(handlerCollection);

            shared = false;
        }

        JettyHttpServer jettyHttpServer = new JettyHttpServer(server, shared);
        if (addr != null)
            jettyHttpServer.bind(addr, backlog);
        return jettyHttpServer;
    }

    @Override
    public HttpsServer createHttpsServer(InetSocketAddress addr, int backlog) throws IOException
    {
        throw new UnsupportedOperationException();
    }
}
