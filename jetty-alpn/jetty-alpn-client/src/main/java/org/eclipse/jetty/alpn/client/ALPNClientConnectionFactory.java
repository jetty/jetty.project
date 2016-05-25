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

package org.eclipse.jetty.alpn.client;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;

public class ALPNClientConnectionFactory extends NegotiatingClientConnectionFactory
{
    private final Executor executor;
    private final List<String> protocols;

    public ALPNClientConnectionFactory(Executor executor, ClientConnectionFactory connectionFactory, List<String> protocols)
    {
        super(connectionFactory);
        this.executor = executor;
        this.protocols = protocols;
        if (protocols.isEmpty())
            throw new IllegalArgumentException("ALPN protocol list cannot be empty");
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        ALPNClientConnection connection = new ALPNClientConnection(endPoint, executor, getClientConnectionFactory(),
                (SSLEngine)context.get(SslClientConnectionFactory.SSL_ENGINE_CONTEXT_KEY), context, protocols);
        return customize(connection, context);
    }
}
