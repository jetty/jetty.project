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

package org.eclipse.jetty.alpn.openjdk8.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class OpenJDK8ClientALPNProcessor implements ALPNProcessor.Client
{
    private static final Logger LOG = Log.getLogger(OpenJDK8ClientALPNProcessor.class);

    private Method alpnProtocols;
    private Method alpnProtocol;

    @Override
    public void init()
    {
        if (JavaVersion.VERSION.getPlatform() != 8)
            throw new IllegalStateException(this + " not applicable for java " + JavaVersion.VERSION);

        try
        {
            // JDK 8u252 has the JDK 9 ALPN API backported.
            // Use reflection so we can build with a JDK version less than 8u252.
            alpnProtocols = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
            alpnProtocol = SSLEngine.class.getMethod("getApplicationProtocol");
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
        ALPNClientConnection alpnConnection = (ALPNClientConnection)connection;
        if (alpnProtocols == null)
        {
            connection.addListener(new ALPNConnectionListener(alpnConnection));
        }
        else
        {
            try
            {
                Object protocols = alpnConnection.getProtocols().toArray(new String[0]);
                SSLParameters sslParameters = sslEngine.getSSLParameters();
                alpnProtocols.invoke(sslParameters, protocols);
                sslEngine.setSSLParameters(sslParameters);
                ((SslConnection.DecryptedEndPoint)connection.getEndPoint()).getSslConnection()
                    .addHandshakeListener(new ALPNSSLListener(alpnConnection));
            }
            catch (IllegalAccessException | InvocationTargetException x)
            {
                throw new IllegalStateException(this + " unable to set ALPN protocols", x);
            }
        }
    }

    private static final class ALPNConnectionListener implements ALPN.ClientProvider, Connection.Listener
    {
        private final ALPNClientConnection alpnConnection;

        private ALPNConnectionListener(ALPNClientConnection connection)
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
        public List<String> protocols()
        {
            return alpnConnection.getProtocols();
        }

        @Override
        public void unsupported()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("unsupported {}", alpnConnection);
            ALPN.remove(alpnConnection.getSSLEngine());
            alpnConnection.selected(null);
        }

        @Override
        public void selected(String protocol)
        {
            alpnConnection.selected(protocol);
        }
    }

    private final class ALPNSSLListener implements SslHandshakeListener
    {
        private final ALPNClientConnection alpnConnection;

        private ALPNSSLListener(ALPNClientConnection connection)
        {
            alpnConnection = connection;
        }

        @Override
        public void handshakeSucceeded(Event event) throws SSLException
        {
            try
            {
                SSLEngine sslEngine = alpnConnection.getSSLEngine();
                String protocol = (String)alpnProtocol.invoke(sslEngine);
                if (LOG.isDebugEnabled())
                    LOG.debug("selected protocol {}", protocol);
                alpnConnection.selected(protocol);
            }
            catch (IllegalAccessException | InvocationTargetException x)
            {
                SSLHandshakeException failure = new SSLHandshakeException(this + " unable to get ALPN protocol");
                throw (SSLHandshakeException)failure.initCause(x);
            }
        }
    }
}
