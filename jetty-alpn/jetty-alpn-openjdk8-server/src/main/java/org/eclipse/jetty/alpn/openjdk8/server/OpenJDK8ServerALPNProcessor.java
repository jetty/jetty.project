//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class OpenJDK8ServerALPNProcessor implements ALPNProcessor.Server
{
    private static final Logger LOG = Log.getLogger(OpenJDK8ServerALPNProcessor.class);
    
    @Override
    public void init()
    {
        if (JavaVersion.VERSION.getPlatform()!=8)
            throw new IllegalStateException(this + " not applicable for java "+JavaVersion.VERSION);
        if (ALPN.class.getClassLoader()!=null)
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
        connection.addListener(new ALPNListener((ALPNServerConnection)connection));
    }

    private final class ALPNListener implements ALPN.ServerProvider, Connection.Listener
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
        public String select(List<String> protocols) throws SSLException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("select {} {}", alpnConnection, protocols);
            alpnConnection.select(protocols);
            return alpnConnection.getProtocol();
        }
    }
}
