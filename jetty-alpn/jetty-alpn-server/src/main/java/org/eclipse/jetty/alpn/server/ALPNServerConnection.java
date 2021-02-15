//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.alpn.server;

import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ALPNServerConnection extends NegotiatingServerConnection
{
    private static final Logger LOG = Log.getLogger(ALPNServerConnection.class);

    public ALPNServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol)
    {
        super(connector, endPoint, engine, protocols, defaultProtocol);
    }

    public void unsupported()
    {
        select(Collections.emptyList());
    }

    public void select(List<String> clientProtocols)
    {
        SSLEngine sslEngine = getSSLEngine();
        List<String> serverProtocols = getProtocols();
        SSLSession sslSession = sslEngine.getHandshakeSession();
        if (sslSession == null)
            sslSession = sslEngine.getSession();
        String tlsProtocol = sslSession.getProtocol();
        String tlsCipher = sslSession.getCipherSuite();
        String negotiated = null;

        // RFC 7301 states that the server picks the protocol
        // that it prefers that is also supported by the client.
        for (String serverProtocol : serverProtocols)
        {
            if (clientProtocols.contains(serverProtocol))
            {
                ConnectionFactory factory = getConnector().getConnectionFactory(serverProtocol);
                if (factory instanceof CipherDiscriminator && !((CipherDiscriminator)factory).isAcceptable(serverProtocol, tlsProtocol, tlsCipher))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Protocol {} not acceptable to {} for {}/{} on {}", serverProtocol, factory, tlsProtocol, tlsCipher, getEndPoint());
                    continue;
                }

                negotiated = serverProtocol;
                break;
            }
        }
        if (negotiated == null)
        {
            if (clientProtocols.isEmpty())
            {
                negotiated = getDefaultProtocol();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not negotiate protocol from client{} and server{} on {}", clientProtocols, serverProtocols, getEndPoint());
                throw new IllegalStateException();
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Protocol selected {} from client{} and server{} on {}", negotiated, clientProtocols, serverProtocols, getEndPoint());
        setProtocol(negotiated);
    }
}
