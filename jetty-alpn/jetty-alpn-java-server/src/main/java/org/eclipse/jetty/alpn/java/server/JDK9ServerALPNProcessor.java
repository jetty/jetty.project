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

public class JDK9ServerALPNProcessor implements ALPNProcessor.Server
{
    @Override
    public void configure(SSLEngine sslEngine)
    {
        sslEngine.setHandshakeApplicationProtocolSelector(this::process);
    }

    private String process(SSLEngine sslEngine, List<String> protocols)
    {
        try
        {
            ALPN.ServerProvider provider = (ALPN.ServerProvider)ALPN.get(sslEngine);
            return provider.select(protocols);
        }
        catch (SSLException x)
        {
            return null;
        }
    }
}
