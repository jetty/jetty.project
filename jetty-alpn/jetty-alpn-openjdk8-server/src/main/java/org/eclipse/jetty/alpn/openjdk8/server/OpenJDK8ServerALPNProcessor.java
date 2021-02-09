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

package org.eclipse.jetty.alpn.openjdk8.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class OpenJDK8ServerALPNProcessor implements ALPNProcessor.Server
{
    private static final Logger LOG = Log.getLogger(OpenJDK8ServerALPNProcessor.class);

    private Method alpnSelector;

    @Override
    public void init()
    {
        if (JavaVersion.VERSION.getPlatform() != 8)
            throw new IllegalStateException(this + " not applicable for java " + JavaVersion.VERSION);

        try
        {
            // JDK 8u252 has the JDK 9 ALPN API backported.
            // Use reflection so we can build with a JDK version less than 8u252.
            alpnSelector = SSLEngine.class.getMethod("setHandshakeApplicationProtocolSelector", BiFunction.class);
            if (LOG.isDebugEnabled())
                LOG.debug("Using OpenJDK ALPN APIs instead of Jetty ALPN APIs");
            return;
        }
        catch (NoSuchMethodException x)
        {
            LOG.ignore(x);
        }

        // Backported ALPN APIs not available.
        if (ALPN.class.getClassLoader() != null)
            throw new IllegalStateException(ALPN.class.getName() + " must be on JVM boot classpath");
        if (LOG.isDebugEnabled())
            ALPN.debug = true;
    }

    @Override
    public boolean appliesTo(SSLEngine sslEngine)
    {
        return sslEngine.getClass().getName().startsWith("sun.security.ssl.");
    }

    @Override
    public void configure(SSLEngine sslEngine, Connection connection)
    {
        if (alpnSelector == null)
        {
            ALPNListener listener = new ALPNListener((ALPNServerConnection)connection);
            connection.addListener(listener);
        }
        else
        {
            try
            {
                ALPNCallback callback = new ALPNCallback((ALPNServerConnection)connection);
                alpnSelector.invoke(sslEngine, callback);
            }
            catch (IllegalAccessException | InvocationTargetException x)
            {
                throw new IllegalStateException(this + " unable to set ALPN selector", x);
            }
        }
    }

    private static final class ALPNListener implements ALPN.ServerProvider, Connection.Listener
    {
        private final ALPNServerConnection alpnConnection;

        private ALPNListener(ALPNServerConnection connection)
        {
            alpnConnection = connection;
        }

        @Override
        public void onOpened(Connection connection)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onOpened {}", alpnConnection);
            ALPN.put(alpnConnection.getSSLEngine(), this);
        }

        @Override
        public void onClosed(Connection connection)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onClosed {}", alpnConnection);
            ALPN.remove(alpnConnection.getSSLEngine());
        }

        @Override
        public void unsupported()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("unsupported {}", alpnConnection);
            alpnConnection.select(Collections.emptyList());
        }

        @Override
        public String select(List<String> protocols)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("select {} {}", alpnConnection, protocols);
            alpnConnection.select(protocols);
            return alpnConnection.getProtocol();
        }
    }

    private static class ALPNCallback implements BiFunction<SSLEngine, List<String>, String>, SslHandshakeListener
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
