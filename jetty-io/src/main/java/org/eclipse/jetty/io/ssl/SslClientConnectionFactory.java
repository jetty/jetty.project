//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * <p>A ClientConnectionFactory that creates client-side {@link SslConnection} instances.</p>
 */
public class SslClientConnectionFactory implements ClientConnectionFactory
{
    public static final String SSL_ENGINE_CONTEXT_KEY = "org.eclipse.jetty.client.ssl.engine";

    private final SslContextFactory sslContextFactory;
    private final ByteBufferPool byteBufferPool;
    private final RetainableByteBufferPool retainableByteBufferPool;
    private final Executor executor;
    private final ClientConnectionFactory connectionFactory;
    private boolean _directBuffersForEncryption = true;
    private boolean _directBuffersForDecryption = true;
    private boolean _requireCloseMessage;

    @Deprecated
    public SslClientConnectionFactory(SslContextFactory sslContextFactory, ByteBufferPool byteBufferPool, Executor executor, ClientConnectionFactory connectionFactory)
    {
        this(sslContextFactory, byteBufferPool, RetainableByteBufferPool.findOrAdapt(null, byteBufferPool), executor, connectionFactory);
    }

    public SslClientConnectionFactory(SslContextFactory sslContextFactory, ByteBufferPool byteBufferPool, RetainableByteBufferPool retainableByteBufferPool, Executor executor, ClientConnectionFactory connectionFactory)
    {
        this.sslContextFactory = Objects.requireNonNull(sslContextFactory, "Missing SslContextFactory");
        this.byteBufferPool = byteBufferPool;
        this.retainableByteBufferPool = retainableByteBufferPool;
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
        SSLEngine engine;
        SocketAddress remote = (SocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
        if (remote instanceof InetSocketAddress)
        {
            InetSocketAddress inetRemote = (InetSocketAddress)remote;
            String host = inetRemote.getHostString();
            int port = inetRemote.getPort();
            engine = sslContextFactory instanceof SslEngineFactory
                ? ((SslEngineFactory)sslContextFactory).newSslEngine(host, port, context)
                : sslContextFactory.newSSLEngine(host, port);
        }
        else
        {
            engine = sslContextFactory.newSSLEngine();
        }
        engine.setUseClientMode(true);
        context.put(SSL_ENGINE_CONTEXT_KEY, engine);

        SslConnection sslConnection = newSslConnection(retainableByteBufferPool, byteBufferPool, executor, endPoint, engine);

        EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
        appEndPoint.setConnection(connectionFactory.newConnection(appEndPoint, context));

        sslConnection.addHandshakeListener(new HTTPSHandshakeListener(context));
        customize(sslConnection, context);

        return sslConnection;
    }

    protected SslConnection newSslConnection(RetainableByteBufferPool retainableByteBufferPool, ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
    {
        return new SslConnection(retainableByteBufferPool, byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption());
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

    /**
     * <p>A factory for {@link SSLEngine} objects.</p>
     * <p>Typically implemented by {@link SslContextFactory.Client}
     * to support more flexible creation of SSLEngine instances.</p>
     */
    public interface SslEngineFactory
    {
        /**
         * <p>Creates a new {@link SSLEngine} instance for the given peer host and port,
         * and with the given context to help the creation of the SSLEngine.</p>
         *
         * @param host the peer host
         * @param port the peer port
         * @param context the {@link ClientConnectionFactory} context
         * @return a new SSLEngine instance
         */
        public SSLEngine newSslEngine(String host, int port, Map<String, Object> context);
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
                SocketAddress address = (SocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
                if (address instanceof InetSocketAddress)
                {
                    String host = ((InetSocketAddress)address).getHostString();
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
}
