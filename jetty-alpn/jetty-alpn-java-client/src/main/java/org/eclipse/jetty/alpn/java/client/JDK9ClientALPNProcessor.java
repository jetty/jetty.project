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

package org.eclipse.jetty.alpn.java.client;

import java.io.UncheckedIOException;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.io.ssl.ALPNProcessor;

public class JDK9ClientALPNProcessor implements ALPNProcessor.Client
{
    @Override
    public void configure(SSLEngine sslEngine, List<String> protocols)
    {
        SSLParameters sslParameters = sslEngine.getSSLParameters();
        sslParameters.setApplicationProtocols(protocols.toArray(new String[0]));
        sslEngine.setSSLParameters(sslParameters);
    }

    @Override
    public void process(SSLEngine sslEngine)
    {
        try
        {
            ALPN.ClientProvider provider = (ALPN.ClientProvider)ALPN.get(sslEngine);
            provider.selected(sslEngine.getApplicationProtocol());
        }
        catch (SSLException x)
        {
            throw new UncheckedIOException(x);
        }
    }
}
