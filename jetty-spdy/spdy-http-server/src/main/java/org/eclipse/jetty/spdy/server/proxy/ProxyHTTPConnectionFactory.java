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


import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;

public class ProxyHTTPConnectionFactory extends AbstractConnectionFactory implements HttpConfiguration.ConnectionFactory
{
    private final short version;
    private final ProxyEngineSelector proxyEngineSelector;
    private final HttpConfiguration httpConfiguration;

    public ProxyHTTPConnectionFactory(HttpConfiguration httpConfiguration,short version, ProxyEngineSelector proxyEngineSelector)
    {
        // replaces http/1.1
        super(HttpVersion.HTTP_1_1.asString());
        this.version = version;
        this.proxyEngineSelector = proxyEngineSelector;
        this.httpConfiguration=httpConfiguration;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        return configure(new ProxyHTTPSPDYConnection(connector, httpConfiguration, endPoint, version, proxyEngineSelector),connector,endPoint);
    }

    @Override
    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

}
