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

package org.eclipse.jetty.server;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public abstract class AbstractConnectionFactory extends ContainerLifeCycle implements ConnectionFactory
{
    private final String _protocol;
    private int _inputbufferSize = 8192;

    protected AbstractConnectionFactory(String protocol)
    {
        _protocol=protocol;
    }

    @Override
    public String getProtocol()
    {
        return _protocol;
    }

    public int getInputBufferSize()
    {
        return _inputbufferSize;
    }

    public void setInputBufferSize(int size)
    {
        _inputbufferSize=size;
    }

    protected AbstractConnection configure(AbstractConnection connection, Connector connector, EndPoint endPoint)
    {
        connection.setInputBufferSize(getInputBufferSize());

        if (connector instanceof ContainerLifeCycle)
        {
            ContainerLifeCycle aggregate = (ContainerLifeCycle)connector;
            for (Connection.Listener listener : aggregate.getBeans(Connection.Listener.class))
                connection.addListener(listener);
        }
        return connection;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}",this.getClass().getSimpleName(),hashCode(),getProtocol());
    }

    public static ConnectionFactory[] getFactories(SslContextFactory sslContextFactory, ConnectionFactory... factories)
    {
        factories=ArrayUtil.removeNulls(factories);

        if (sslContextFactory==null)
            return factories;

        for (ConnectionFactory factory : factories)
        {
            if (factory instanceof HttpConfiguration.ConnectionFactory)
            {
                HttpConfiguration config = ((HttpConfiguration.ConnectionFactory)factory).getHttpConfiguration();
                if (config.getCustomizer(SecureRequestCustomizer.class)==null)
                    config.addCustomizer(new SecureRequestCustomizer());
            }
        }
        return ArrayUtil.prependToArray(new SslConnectionFactory(sslContextFactory,factories[0].getProtocol()),factories,ConnectionFactory.class);

    }
}
