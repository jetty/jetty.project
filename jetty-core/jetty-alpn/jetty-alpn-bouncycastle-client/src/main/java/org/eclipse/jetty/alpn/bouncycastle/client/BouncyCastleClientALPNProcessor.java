//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.alpn.bouncycastle.client;

import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection.SslEndPoint;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BouncyCastleClientALPNProcessor implements ALPNProcessor.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(BouncyCastleClientALPNProcessor.class);

    @Override
    public boolean appliesTo(SSLEngine sslEngine)
    {
        return sslEngine.getClass().getName().startsWith("org.bouncycastle.jsse.provider.");
    }

    @Override
    public void configure(SSLEngine sslEngine, Connection connection)
    {
        try
        {
            ALPNClientConnection alpn = (ALPNClientConnection)connection;
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            List<String> protocols = alpn.getProtocols();
            sslParameters.setApplicationProtocols(protocols.toArray(new String[0]));
            sslEngine.setSSLParameters(sslParameters);
            SslEndPoint sslEndPoint = (SslEndPoint)connection.getEndPoint();
            sslEndPoint.getSslConnection().addHandshakeListener(new ALPNListener(alpn));
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
                String protocol = sslEngine.getApplicationProtocol();
                if (LOG.isDebugEnabled())
                    LOG.debug("Selected {} for {}", protocol, alpnConnection);
                alpnConnection.selected(protocol);
            }
            catch (Throwable e)
            {
                LOG.warn("Unable to process BouncyCastle ApplicationProtocol for {}", alpnConnection, e);
                alpnConnection.selected(null);
            }
        }
    }
}
