//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.NegotiatingClientConnectionFactory;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class ALPNClientConnectionFactory extends NegotiatingClientConnectionFactory implements SslHandshakeListener
{
    private final SslHandshakeListener alpnListener = new ALPNListener();
    private final Executor executor;
    private final List<String> protocols;
    private final ALPNProcessor.Client alpnProcessor;

    public ALPNClientConnectionFactory(Executor executor, ClientConnectionFactory connectionFactory, List<String> protocols)
    {
        super(connectionFactory);
        if (protocols.isEmpty())
            throw new IllegalArgumentException("ALPN protocol list cannot be empty");
        this.executor = executor;
        this.protocols = protocols;
        Iterator<ALPNProcessor.Client> processors = ServiceLoader.load(ALPNProcessor.Client.class).iterator();
        alpnProcessor = processors.hasNext() ? processors.next() : ALPNProcessor.Client.NOOP;
    }

    public ALPNProcessor.Client getALPNProcessor()
    {
        return alpnProcessor;
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        SSLEngine sslEngine = (SSLEngine)context.get(SslClientConnectionFactory.SSL_ENGINE_CONTEXT_KEY);
        getALPNProcessor().configure(sslEngine, protocols);
        ContainerLifeCycle connector = (ContainerLifeCycle)context.get(ClientConnectionFactory.CONNECTOR_CONTEXT_KEY);
        // Method addBean() has set semantic, so the listener is added only once.
        connector.addBean(alpnListener);
        ALPNClientConnection connection = new ALPNClientConnection(endPoint, executor, getClientConnectionFactory(),
                sslEngine, context, protocols);
        return customize(connection, context);
    }

    private class ALPNListener implements SslHandshakeListener
    {
        @Override
        public void handshakeSucceeded(Event event)
        {
            getALPNProcessor().process(event.getSSLEngine());
        }

        @Override
        public void handshakeFailed(Event event, Throwable failure)
        {
        }
    }
}
