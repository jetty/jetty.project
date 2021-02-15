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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class JDK9ServerALPNProcessor implements ALPNProcessor.Server, SslHandshakeListener
{
    private static final Logger LOG = Log.getLogger(JDK9ServerALPNProcessor.class);

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
                LOG.debug("TLS handshake failed " + alpnConnection, failure);
        }
    }
}
