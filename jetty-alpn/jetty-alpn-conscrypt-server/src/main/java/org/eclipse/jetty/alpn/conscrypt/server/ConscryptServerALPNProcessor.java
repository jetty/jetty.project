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

package org.eclipse.jetty.alpn.conscrypt.server;

import java.security.Security;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import org.conscrypt.ApplicationProtocolSelector;
import org.conscrypt.Conscrypt;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.alpn.server.ALPNServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ConscryptServerALPNProcessor implements ALPNProcessor.Server
{
    private static final Logger LOG = Log.getLogger(ConscryptServerALPNProcessor.class);

    @Override
    public void init()
    {
        if (Security.getProvider("Conscrypt") == null)
        {
            Security.addProvider(new OpenSSLProvider());
            if (LOG.isDebugEnabled())
                LOG.debug("Added Conscrypt provider");
        }
    }

    @Override
    public boolean appliesTo(SSLEngine sslEngine)
    {
        return sslEngine.getClass().getName().startsWith("org.conscrypt.");
    }

    @Override
    public void configure(SSLEngine sslEngine, Connection connection)
    {
        try
        {
            Conscrypt.setApplicationProtocolSelector(sslEngine, new ALPNCallback((ALPNServerConnection)connection));
        }
        catch (RuntimeException x)
        {
            throw x;
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    private final class ALPNCallback extends ApplicationProtocolSelector implements SslHandshakeListener
    {
        private final ALPNServerConnection alpnConnection;

        private ALPNCallback(ALPNServerConnection connection)
        {
            alpnConnection = connection;
            ((DecryptedEndPoint)alpnConnection.getEndPoint()).getSslConnection().addHandshakeListener(this);
        }

        @Override
        public String selectApplicationProtocol(SSLEngine engine, List<String> protocols)
        {
            alpnConnection.select(protocols);
            String protocol = alpnConnection.getProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("Selected {} among {} for {}", protocol, protocols, alpnConnection);
            return protocol;
        }

        @Override
        public String selectApplicationProtocol(SSLSocket socket, List<String> protocols)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handshakeSucceeded(Event event)
        {
            String protocol = alpnConnection.getProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("TLS handshake succeeded, protocol={} for {}", protocol, alpnConnection);
            if (protocol == null)
                alpnConnection.unsupported();
        }

        @Override
        public void handshakeFailed(Event event, Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("TLS handshake failed " + alpnConnection, failure);
        }
    }
}
