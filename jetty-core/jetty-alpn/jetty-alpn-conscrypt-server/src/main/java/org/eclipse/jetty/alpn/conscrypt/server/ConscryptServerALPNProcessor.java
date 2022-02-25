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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConscryptServerALPNProcessor implements ALPNProcessor.Server
{
    private static final Logger LOG = LoggerFactory.getLogger(ConscryptServerALPNProcessor.class);

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
                LOG.debug("TLS handshake failed {}", alpnConnection, failure);
        }
    }
}
