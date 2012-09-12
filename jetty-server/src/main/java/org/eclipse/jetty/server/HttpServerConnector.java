//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

public class HttpServerConnector extends SelectChannelConnector
{
    public HttpServerConnector(
        @Name("server") Server server)
    {
        this(server,null,null,null,null,null,0,0);
    }

    public HttpServerConnector(
        @Name("server") Server server, 
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,sslContextFactory, null, null, null, 0, 0);
    }

    public HttpServerConnector(
        @Name("server") Server server, 
        @Name("connectionFactory") HttpConnectionFactory connectionFactory)
    {
        this(server,connectionFactory,null, null, null, null, 0, 0);
    }

    public HttpServerConnector(
        @Name("server") Server server, 
        @Name("connectionFactory") HttpConnectionFactory connectionFactory,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,connectionFactory,sslContextFactory, null, null, null, 0, 0);
    }

    public HttpServerConnector(
        @Name("server") Server server, 
        @Name("connectionFactory") HttpConnectionFactory connectionFactory,
        @Name("sslContextFactory") SslContextFactory sslContextFactory, 
        @Name("executor") Executor executor, 
        @Name("scheduler") Scheduler scheduler, 
        @Name("bufferPool") ByteBufferPool pool, 
        @Name("acceptors") int acceptors, 
        @Name("selectors") int selectors)
    {
        super(server,executor,scheduler,pool,acceptors,selectors,AbstractConnectionFactory.getFactories(sslContextFactory,connectionFactory==null?new HttpConnectionFactory():connectionFactory));
    }
}
