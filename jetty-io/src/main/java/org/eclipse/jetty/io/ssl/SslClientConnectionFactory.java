//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslClientConnectionFactory implements ClientConnectionFactory
{
    public static final String SSL_PEER_HOST_CONTEXT_KEY = "ssl.peer.host";
    public static final String SSL_PEER_PORT_CONTEXT_KEY = "ssl.peer.port";
    public static final String SSL_ENGINE_CONTEXT_KEY = "ssl.engine";

    private final SslContextFactory sslContextFactory;
    private final ByteBufferPool byteBufferPool;
    private final Executor executor;
    private final ClientConnectionFactory connectionFactory;

    public SslClientConnectionFactory(SslContextFactory sslContextFactory, ByteBufferPool byteBufferPool, Executor executor, ClientConnectionFactory connectionFactory)
    {
        this.sslContextFactory = sslContextFactory;
        this.byteBufferPool = byteBufferPool;
        this.executor = executor;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        String host = (String)context.get(SSL_PEER_HOST_CONTEXT_KEY);
        int port = (Integer)context.get(SSL_PEER_PORT_CONTEXT_KEY);
        SSLEngine engine = sslContextFactory.newSSLEngine(host, port);
        engine.setUseClientMode(true);
        context.put(SSL_ENGINE_CONTEXT_KEY, engine);

        SslConnection sslConnection = newSslConnection(byteBufferPool, executor, endPoint, engine);
        sslConnection.setRenegotiationAllowed(sslContextFactory.isRenegotiationAllowed());
        endPoint.setConnection(sslConnection);
        EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
        appEndPoint.setConnection(connectionFactory.newConnection(appEndPoint, context));

        return sslConnection;
    }

    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
    {
        return new SslConnection(byteBufferPool, executor, endPoint, engine);
    }
}
