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

package org.eclipse.jetty.alpn.conscrypt.client;

import java.security.Security;
import javax.net.ssl.SSLEngine;

import org.conscrypt.Conscrypt;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConscryptClientALPNProcessor implements ALPNProcessor.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(ConscryptClientALPNProcessor.class);

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
            ALPNClientConnection alpn = (ALPNClientConnection)connection;
            String[] protocols = alpn.getProtocols().toArray(new String[0]);
            Conscrypt.setApplicationProtocols(sslEngine, protocols);
            ((SslConnection.DecryptedEndPoint)connection.getEndPoint()).getSslConnection()
                .addHandshakeListener(new ALPNListener(alpn));
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

    private final class ALPNListener implements SslHandshakeListener
    {
        private final ALPNClientConnection alpnConnection;

        private ALPNListener(ALPNClientConnection connection)
        {
            alpnConnection = connection;
        }

        @Override
        public void handshakeSucceeded(Event event)
        {
            try
            {
                SSLEngine sslEngine = alpnConnection.getSSLEngine();
                String protocol = Conscrypt.getApplicationProtocol(sslEngine);
                if (LOG.isDebugEnabled())
                    LOG.debug("Selected {} for {}", protocol, alpnConnection);
                alpnConnection.selected(protocol);
            }
            catch (Throwable e)
            {
                LOG.warn("Unable to process Conscrypt ApplicationProtocol for {}", alpnConnection, e);
                alpnConnection.selected(null);
            }
        }
    }
}
