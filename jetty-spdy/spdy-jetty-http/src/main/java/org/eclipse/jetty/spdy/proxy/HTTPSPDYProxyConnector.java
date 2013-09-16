//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.spdy.ServerSPDYAsyncConnectionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.http.AbstractHTTPSPDYServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPSPDYProxyConnector extends AbstractHTTPSPDYServerConnector
{
    public HTTPSPDYProxyConnector(ProxyEngineSelector proxyEngineSelector)
    {
        this(proxyEngineSelector, null);
    }

    public HTTPSPDYProxyConnector(ProxyEngineSelector proxyEngineSelector, SslContextFactory sslContextFactory)
    {
        super(proxyEngineSelector, sslContextFactory);
        clearAsyncConnectionFactories();

        putAsyncConnectionFactory("spdy/3", new ServerSPDYAsyncConnectionFactory(SPDY.V3, getByteBufferPool(), getExecutor(), getScheduler(), proxyEngineSelector));
        putAsyncConnectionFactory("spdy/2", new ServerSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), proxyEngineSelector));
        putAsyncConnectionFactory("http/1.1", new ProxyHTTPAsyncConnectionFactory(this, SPDY.V2, proxyEngineSelector));
        setDefaultAsyncConnectionFactory(getAsyncConnectionFactory("http/1.1"));
    }
}
