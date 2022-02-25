//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * <p>Provides the common handling for {@link ConnectionFactory} implementations.</p>
 */
@ManagedObject
public abstract class AbstractConnectionFactory extends ContainerLifeCycle implements ConnectionFactory
{
    private final String _protocol;
    private final List<String> _protocols;
    private int _inputbufferSize = 8192;

    protected AbstractConnectionFactory(String protocol)
    {
        _protocol = protocol;
        _protocols = Collections.unmodifiableList(Arrays.asList(new String[]{protocol}));
    }

    protected AbstractConnectionFactory(String... protocols)
    {
        _protocol = protocols[0];
        _protocols = Collections.unmodifiableList(Arrays.asList(protocols));
    }

    @Override
    @ManagedAttribute(value = "The protocol name", readonly = true)
    public String getProtocol()
    {
        return _protocol;
    }

    @Override
    public List<String> getProtocols()
    {
        return _protocols;
    }

    @ManagedAttribute("The buffer size used to read from the network")
    public int getInputBufferSize()
    {
        return _inputbufferSize;
    }

    public void setInputBufferSize(int size)
    {
        _inputbufferSize = size;
    }

    protected String findNextProtocol(Connector connector)
    {
        return findNextProtocol(connector, getProtocol());
    }

    protected static String findNextProtocol(Connector connector, String currentProtocol)
    {
        String nextProtocol = null;
        for (Iterator<String> it = connector.getProtocols().iterator(); it.hasNext(); )
        {
            String protocol = it.next();
            if (currentProtocol.equalsIgnoreCase(protocol))
            {
                nextProtocol = it.hasNext() ? it.next() : null;
                break;
            }
        }
        return nextProtocol;
    }

    protected AbstractConnection configure(AbstractConnection connection, Connector connector, EndPoint endPoint)
    {
        connection.setInputBufferSize(getInputBufferSize());

        // Add Connection.Listeners from Connector
        connector.getEventListeners().forEach(connection::addEventListener);

        // Add Connection.Listeners from this factory
        getEventListeners().forEach(connection::addEventListener);

        return connection;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", this.getClass().getSimpleName(), hashCode(), getProtocols());
    }

    public static ConnectionFactory[] getFactories(SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        factories = ArrayUtil.removeNulls(factories);

        if (sslContextFactory == null)
            return factories;

        for (ConnectionFactory factory : factories)
        {
            if (factory instanceof HttpConfiguration.ConnectionFactory)
            {
                HttpConfiguration config = ((HttpConfiguration.ConnectionFactory)factory).getHttpConfiguration();
                if (config.getCustomizer(SecureRequestCustomizer.class) == null)
                    config.addCustomizer(new SecureRequestCustomizer());
            }
        }
        return ArrayUtil.prependToArray(new SslConnectionFactory(sslContextFactory, factories[0].getProtocol()), factories, ConnectionFactory.class);
    }
}
