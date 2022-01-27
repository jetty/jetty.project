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

package org.eclipse.jetty.alpn.java.server;

import java.util.List;
import java.util.function.BiFunction;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.alpn.server.ALPNServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.JavaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDK9ServerALPNProcessor implements ALPNProcessor.Server, SslHandshakeListener
{
    private static final Logger LOG = LoggerFactory.getLogger(JDK9ServerALPNProcessor.class);

    @Override
    public void init()
    {
        if (JavaVersion.VERSION.getPlatform() < 9)
            throw new IllegalStateException(this + " not applicable for java " + JavaVersion.VERSION);
    }

    @Override
    public boolean appliesTo(SSLEngine sslEngine)
    {
        Module module = sslEngine.getClass().getModule();
        return module != null && "java.base".equals(module.getName());
    }

    @Override
    public void configure(SSLEngine sslEngine, Connection connection)
    {
        sslEngine.setHandshakeApplicationProtocolSelector(new ALPNCallback((ALPNServerConnection)connection));
    }

    private static final class ALPNCallback implements BiFunction<SSLEngine, List<String>, String>, SslHandshakeListener
    {
        private final ALPNServerConnection alpnConnection;

        private ALPNCallback(ALPNServerConnection connection)
        {
            alpnConnection = connection;
            ((SslConnection.DecryptedEndPoint)alpnConnection.getEndPoint()).getSslConnection().addHandshakeListener(this);
        }

        @Override
        public String apply(SSLEngine engine, List<String> protocols)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("apply {} {}", alpnConnection, protocols);
                alpnConnection.select(protocols);
                return alpnConnection.getProtocol();
            }
            catch (Throwable x)
            {
                // Cannot negotiate the protocol, return null to have
                // JSSE send Alert.NO_APPLICATION_PROTOCOL to the client.
                return null;
            }
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
