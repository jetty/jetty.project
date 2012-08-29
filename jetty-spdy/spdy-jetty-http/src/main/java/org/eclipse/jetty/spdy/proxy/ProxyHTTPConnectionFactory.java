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

import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;

public class ProxyHTTPConnectionFactory implements ConnectionFactory
{
    private final Connector connector;
    private final short version;
    private final ProxyEngineSelector proxyEngineSelector;

    public ProxyHTTPConnectionFactory(Connector connector, short version, ProxyEngineSelector proxyEngineSelector)
    {
        this.connector = connector;
        this.version = version;
        this.proxyEngineSelector = proxyEngineSelector;
    }

    @Override
    public Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment)
    {
        return new ProxyHTTPSPDYConnection(connector, endPoint, version, proxyEngineSelector);
    }
}
