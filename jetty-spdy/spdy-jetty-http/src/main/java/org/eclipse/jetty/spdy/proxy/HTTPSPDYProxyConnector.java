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


package org.eclipse.jetty.spdy.proxy;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.ServerSPDYConnectionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPSPDYProxyConnector extends SPDYServerConnector
{
    public HTTPSPDYProxyConnector(Server server, ProxyEngineSelector proxyEngineSelector)
    {
        this(server, null, proxyEngineSelector);
    }

    public HTTPSPDYProxyConnector(Server server, SslContextFactory sslContextFactory, ProxyEngineSelector proxyEngineSelector)
    {
        super(server, sslContextFactory, proxyEngineSelector);
        clearConnectionFactories();

        putConnectionFactory("spdy/3", new ServerSPDYConnectionFactory(SPDY.V3, getByteBufferPool(), getExecutor(), getScheduler(), proxyEngineSelector));
        putConnectionFactory("spdy/2", new ServerSPDYConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), proxyEngineSelector));
        putConnectionFactory("http/1.1", new ProxyHTTPConnectionFactory(this, SPDY.V2, proxyEngineSelector));
        setDefaultConnectionFactory(getConnectionFactory("http/1.1"));
    }
}
