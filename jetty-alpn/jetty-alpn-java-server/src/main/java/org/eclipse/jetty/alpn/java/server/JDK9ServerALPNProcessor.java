//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class JDK9ServerALPNProcessor implements ALPNProcessor.Server, SslHandshakeListener
{
    private static final Logger LOG = Log.getLogger(JDK9ServerALPNProcessor.class);

    @Override
    public void configure(SSLEngine sslEngine)
    {
        sslEngine.setHandshakeApplicationProtocolSelector(this::process);
    }

    private String process(SSLEngine sslEngine, List<String> protocols)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ALPN selecting among client{}", protocols);
            ALPN.ServerProvider provider = (ALPN.ServerProvider)ALPN.remove(sslEngine);
            return provider.select(protocols);
        }
        catch (SSLException x)
        {
            return null;
        }
    }

    @Override
    public void handshakeSucceeded(Event event)
    {
        ALPN.ServerProvider provider = (ALPN.ServerProvider)ALPN.remove(event.getSSLEngine());
        if (provider != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ALPN unsupported by client");
            provider.unsupported();
        }
    }

    @Override
    public void handshakeFailed(Event event, Throwable failure)
    {
    }
}
