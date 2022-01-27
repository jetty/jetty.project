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

package org.eclipse.jetty.alpn.java.client;

import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.JavaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDK9ClientALPNProcessor implements ALPNProcessor.Client
{
    private static final Logger LOG = LoggerFactory.getLogger(JDK9ClientALPNProcessor.class);

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
        ALPNClientConnection alpn = (ALPNClientConnection)connection;
        SSLParameters sslParameters = sslEngine.getSSLParameters();
        List<String> protocols = alpn.getProtocols();
        sslParameters.setApplicationProtocols(protocols.toArray(new String[0]));
        sslEngine.setSSLParameters(sslParameters);
        ((DecryptedEndPoint)connection.getEndPoint()).getSslConnection()
            .addHandshakeListener(new ALPNListener(alpn));
    }

    private static final class ALPNListener implements SslHandshakeListener
    {
        private final ALPNClientConnection alpnConnection;

        private ALPNListener(ALPNClientConnection connection)
        {
            alpnConnection = connection;
        }

        @Override
        public void handshakeSucceeded(Event event)
        {
            String protocol = alpnConnection.getSSLEngine().getApplicationProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("selected protocol '{}'", protocol);
            if (protocol != null && !protocol.isEmpty())
                alpnConnection.selected(protocol);
            else
                alpnConnection.selected(null);
        }
    }
}
