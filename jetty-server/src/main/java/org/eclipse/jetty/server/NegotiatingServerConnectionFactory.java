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

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;

public abstract class NegotiatingServerConnectionFactory extends AbstractConnectionFactory
{
    private final List<String> protocols;
    private String defaultProtocol;

    public NegotiatingServerConnectionFactory(String protocol, String... protocols)
    {
        super(protocol);
        this.protocols = Arrays.asList(protocols);
    }

    public String getDefaultProtocol()
    {
        return defaultProtocol;
    }

    public void setDefaultProtocol(String defaultProtocol)
    {
        this.defaultProtocol = defaultProtocol;
    }

    public List<String> getProtocols()
    {
        return protocols;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        List<String> protocols = this.protocols;
        if (protocols.isEmpty())
        {
            protocols = connector.getProtocols();
            Iterator<String> i = protocols.iterator();
            while (i.hasNext())
            {
                String protocol = i.next();
                String prefix = "ssl-";
                if (protocol.regionMatches(true, 0, prefix, 0, prefix.length()) || protocol.equalsIgnoreCase("alpn"))
                {
                    i.remove();
                }
            }
        }

        String dft = defaultProtocol;
        if (dft == null && !protocols.isEmpty())
            dft = protocols.get(0);

        SSLEngine engine = null;
        EndPoint ep = endPoint;
        while (engine == null && ep != null)
        {
            // TODO make more generic
            if (ep instanceof SslConnection.DecryptedEndPoint)
                engine = ((SslConnection.DecryptedEndPoint)ep).getSslConnection().getSSLEngine();
            else
                ep = null;
        }

        return configure(newServerConnection(connector, endPoint, engine, protocols, dft), connector, endPoint);
    }

    protected abstract AbstractConnection newServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol);

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s,%s}", getClass().getSimpleName(), hashCode(), getProtocol(), getDefaultProtocol(), getProtocols());
    }
}
