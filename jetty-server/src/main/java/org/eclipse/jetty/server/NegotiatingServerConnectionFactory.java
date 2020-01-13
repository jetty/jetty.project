//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;

public abstract class NegotiatingServerConnectionFactory extends AbstractConnectionFactory
{
    private final List<String> negotiatedProtocols;
    private String defaultProtocol;

    public NegotiatingServerConnectionFactory(String protocol, String... negotiatedProtocols)
    {
        super(protocol);
        this.negotiatedProtocols = new ArrayList<>();
        if (negotiatedProtocols != null)
        {
            // Trim the values, as they may come from XML configuration.
            for (String p : negotiatedProtocols)
            {
                p = p.trim();
                if (!p.isEmpty())
                    this.negotiatedProtocols.add(p);
            }
        }
    }

    public String getDefaultProtocol()
    {
        return defaultProtocol;
    }

    public void setDefaultProtocol(String defaultProtocol)
    {
        // Trim the value, as it may come from XML configuration.
        String dft = defaultProtocol == null ? "" : defaultProtocol.trim();
        this.defaultProtocol = dft.isEmpty() ? null : dft;
    }

    public List<String> getNegotiatedProtocols()
    {
        return negotiatedProtocols;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        List<String> negotiated = this.negotiatedProtocols;
        if (negotiated.isEmpty())
        {
            // Generate list of protocols that we can negotiate.
            negotiated = connector.getProtocols().stream()
                .filter(p ->
                {
                    ConnectionFactory f = connector.getConnectionFactory(p);
                    return !(f instanceof SslConnectionFactory) && !(f instanceof NegotiatingServerConnectionFactory);
                })
                .collect(Collectors.toList());
        }

        // If default protocol is not set, then it is
        // either HTTP/1.1 or the first protocol given.
        String dft = defaultProtocol;
        if (dft == null && !negotiated.isEmpty())
        {
            dft = negotiated.stream()
                .filter(HttpVersion.HTTP_1_1::is)
                .findFirst()
                .orElse(negotiated.get(0));
        }

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

        return configure(newServerConnection(connector, endPoint, engine, negotiated, dft), connector, endPoint);
    }

    protected abstract AbstractConnection newServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol);

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s,%s}", getClass().getSimpleName(), hashCode(), getProtocols(), getDefaultProtocol(), getNegotiatedProtocols());
    }
}
