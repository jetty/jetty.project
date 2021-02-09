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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
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

            HandlerCollection handlerCollection = new HandlerCollection();
            handlerCollection.setHandlers(new Handler[]{new ContextHandlerCollection(), new DefaultHandler()});
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
