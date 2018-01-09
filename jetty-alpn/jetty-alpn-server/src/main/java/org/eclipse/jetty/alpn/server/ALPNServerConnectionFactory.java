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

package org.eclipse.jetty.alpn.server;

import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.ALPNProcessor;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NegotiatingServerConnectionFactory;
import org.eclipse.jetty.util.annotation.Name;

public class ALPNServerConnectionFactory extends NegotiatingServerConnectionFactory implements SslHandshakeListener
{
    private final ALPNProcessor.Server alpnProcessor;

    public ALPNServerConnectionFactory(String protocols)
    {
        this(protocols.trim().split(",", 0));
    }

    public ALPNServerConnectionFactory(@Name("protocols") String... protocols)
    {
        super("alpn", protocols);
        checkProtocolNegotiationAvailable();
        Iterator<ALPNProcessor.Server> processors = ServiceLoader.load(ALPNProcessor.Server.class).iterator();
        alpnProcessor = processors.hasNext() ? processors.next() : ALPNProcessor.Server.NOOP;
    }

    public ALPNProcessor.Server getALPNProcessor()
    {
        return alpnProcessor;
    }

    @Override
    protected AbstractConnection newServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol)
    {
        getALPNProcessor().configure(engine);
        return new ALPNServerConnection(connector, endPoint, engine, protocols, defaultProtocol);
    }

    @Override
    public void handshakeSucceeded(Event event)
    {
        if (alpnProcessor instanceof SslHandshakeListener)
            ((SslHandshakeListener)alpnProcessor).handshakeSucceeded(event);
    }

    @Override
    public void handshakeFailed(Event event, Throwable failure)
    {
        if (alpnProcessor instanceof SslHandshakeListener)
            ((SslHandshakeListener)alpnProcessor).handshakeFailed(event, failure);
    }
}
