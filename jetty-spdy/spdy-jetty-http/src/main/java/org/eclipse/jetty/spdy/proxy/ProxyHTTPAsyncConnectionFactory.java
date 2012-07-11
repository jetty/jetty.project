//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================


package org.eclipse.jetty.spdy.proxy;

import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.http.ServerHTTPAsyncConnectionFactory;

public class ProxyHTTPAsyncConnectionFactory extends ServerHTTPAsyncConnectionFactory
{
    private final short version;
    private final ProxyEngine proxyEngine;

    public ProxyHTTPAsyncConnectionFactory(SPDYServerConnector connector, short version, ProxyEngine proxyEngine)
    {
        super(connector);
        this.version = version;
        this.proxyEngine = proxyEngine;
    }

    @Override
    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        return new ProxyHTTPSPDYAsyncConnection(getConnector(), endPoint, version, proxyEngine);
    }
}
