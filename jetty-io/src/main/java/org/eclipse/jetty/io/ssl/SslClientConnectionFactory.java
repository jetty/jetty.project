//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslClientConnectionFactory implements ClientConnectionFactory
{
    public static final String SSL_ENGINE_CONTEXT_KEY = "org.eclipse.jetty.client.ssl.engine";

    private final SslContextFactory sslContextFactory;
    private final ByteBufferPool byteBufferPool;
    private final Executor executor;
    private final ClientConnectionFactory connectionFactory;
    private boolean _directBuffersForEncryption = true;
    private boolean _directBuffersForDecryption = true;
    private boolean _requireCloseMessage;

    public SslClientConnectionFactory(SslContextFactory sslContextFactory, ByteBufferPool byteBufferPool, Executor executor, ClientConnectionFactory connectionFactory)
    {
        this.sslContextFactory = Objects.requireNonNull(sslContextFactory, "Missing SslContextFactory");
        this.byteBufferPool = byteBufferPool;
        this.executor = executor;
        this.connectionFactory = connectionFactory;
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return connectionFactory;
    }

    public void setDirectBuffersForEncryption(boolean useDirectBuffers)
    {
        this._directBuffersForEncryption = useDirectBuffers;
    }

    public void setDirectBuffersForDecryption(boolean useDirectBuffers)
    {
        this._directBuffersForDecryption = useDirectBuffers;
    }

    public boolean isDirectBuffersForDecryption()
    {
        return _directBuffersForDecryption;
    }

    public boolean isDirectBuffersForEncryption()
    {
        return _directBuffersForEncryption;
    }

    /**
     * @return whether peers must send the TLS {@code close_notify} message
     * @see SslConnection#isRequireCloseMessage()
     */
    public boolean isRequireCloseMessage()
    {
        return _requireCloseMessage;
    }

    /**
     * @param requireCloseMessage whether peers must send the TLS {@code close_notify} message
     * @see SslConnection#setRequireCloseMessage(boolean)
     */
    public void setRequireCloseMessage(boolean requireCloseMessage)
    {
        _requireCloseMessage = requireCloseMessage;
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        InetSocketAddress address = (InetSocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
        SSLEngine engine = sslContextFactory.newSSLEngine(address);
        engine.setUseClientMode(true);
        context.put(SSL_ENGINE_CONTEXT_KEY, engine);

        SslConnection sslConnection = newSslConnection(byteBufferPool, executor, endPoint, engine);

        EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
        appEndPoint.setConnection(connectionFactory.newConnection(appEndPoint, context));

        sslConnection.addHandshakeListener(new HTTPSHandshakeListener(context));
        customize(sslConnection, context);

        return sslConnection;
    }

    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
    {
        return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption());
    }

    @Override
    public Connection customize(Connection connection, Map<String, Object> context)
    {
        if (connection instanceof SslConnection)
        {
            SslConnection sslConnection = (SslConnection)connection;
            sslConnection.setRenegotiationAllowed(sslContextFactory.isRenegotiationAllowed());
            sslConnection.setRenegotiationLimit(sslContextFactory.getRenegotiationLimit());
            sslConnection.setRequireCloseMessage(isRequireCloseMessage());
            ContainerLifeCycle client = (ContainerLifeCycle)context.get(ClientConnectionFactory.CLIENT_CONTEXT_KEY);
            if (client != null)
                client.getBeans(SslHandshakeListener.class).forEach(sslConnection::addHandshakeListener);
        }
        return ClientConnectionFactory.super.customize(connection, context);
    }

    private class HTTPSHandshakeListener implements SslHandshakeListener
    {
        private final Map<String, Object> context;

        private HTTPSHandshakeListener(Map<String, Object> context)
        {
            this.context = context;
        }

        @Override
        public void handshakeSucceeded(Event event) throws SSLException
        {
            HostnameVerifier verifier = sslContextFactory.getHostnameVerifier();
            if (verifier != null)
            {
                InetSocketAddress address = (InetSocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
                String host = address.getHostString();
                try
                {
                    if (!verifier.verify(host, event.getSSLEngine().getSession()))
                        throw new SSLPeerUnverifiedException("Host name verification failed for host: " + host);
                }
                catch (SSLException x)
                {
                    throw x;
                }
                catch (Throwable x)
                {
                    throw (SSLException)new SSLPeerUnverifiedException("Host name verification failed for host: " + host).initCause(x);
                }
            }
        }
    }
}
