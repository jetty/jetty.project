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


package org.eclipse.jetty.server;

import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpServerConnectionFactory implements ConnectionFactory
{
    private final Connector connector;
    private final HttpConfiguration configuration;

    public HttpServerConnectionFactory(Connector connector)
    {
        this(connector, new HttpConfiguration(connector.getSslContextFactory(), connector.getSslContextFactory() != null));
    }
    public HttpServerConnectionFactory(Connector connector, HttpConfiguration configuration)
    {
        this.connector = connector;
        this.configuration = configuration;
    }

    public Connector getConnector()
    {
        return connector;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return configuration;
    }

    @Override
    public Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment)
    {
        SslContextFactory sslContextFactory = connector.getSslContextFactory();
        if (sslContextFactory != null)
        {
            SSLEngine engine = sslContextFactory.newSSLEngine(endPoint.getRemoteAddress());
            engine.setUseClientMode(false);
            SslConnection sslConnection = new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine);
            Connection httpConnection = new HttpConnection(getHttpConfiguration(), connector, sslConnection.getDecryptedEndPoint());
            sslConnection.getDecryptedEndPoint().setConnection(httpConnection);
            httpConnection.onOpen();
            return sslConnection;
        }
        else
        {
            return new HttpConnection(getHttpConfiguration(), getConnector(), endPoint);
        }
    }
}
