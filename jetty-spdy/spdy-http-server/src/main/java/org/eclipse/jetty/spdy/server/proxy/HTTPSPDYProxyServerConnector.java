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


package org.eclipse.jetty.spdy.server.proxy;

import java.util.Objects;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPSPDYProxyServerConnector extends ServerConnector
{
    public HTTPSPDYProxyServerConnector(Server server, ProxyEngineSelector proxyEngineSelector)
    {
        this(server, new HttpConfiguration(), proxyEngineSelector);
    }

    public HTTPSPDYProxyServerConnector(Server server, HttpConfiguration config, ProxyEngineSelector proxyEngineSelector)
    {
        super(server, (SslContextFactory)null, new ProxyHTTPConnectionFactory(config, SPDY.V2, proxyEngineSelector));
    }

    public HTTPSPDYProxyServerConnector(Server server, SslContextFactory sslContextFactory, ProxyEngineSelector proxyEngineSelector)
    {
        this(server, sslContextFactory, new HttpConfiguration(), proxyEngineSelector);
    }

    public HTTPSPDYProxyServerConnector(Server server, SslContextFactory sslContextFactory, HttpConfiguration config, ProxyEngineSelector proxyEngineSelector)
    {
        this(server, sslContextFactory, config, proxyEngineSelector, new NPNServerConnectionFactory("spdy/3", "spdy/2", "http/1.1"));
    }

    public HTTPSPDYProxyServerConnector(Server server, SslContextFactory sslContextFactory, HttpConfiguration config, ProxyEngineSelector proxyEngineSelector, NegotiatingServerConnectionFactory negotiatingFactory)
    {
        super(server, Objects.requireNonNull(sslContextFactory), negotiatingFactory,
                new SPDYServerConnectionFactory(SPDY.V3, proxyEngineSelector),
                new SPDYServerConnectionFactory(SPDY.V2, proxyEngineSelector),
                new ProxyHTTPConnectionFactory(config, SPDY.V2, proxyEngineSelector));
        negotiatingFactory.setDefaultProtocol("http/1.1");
    }
}
