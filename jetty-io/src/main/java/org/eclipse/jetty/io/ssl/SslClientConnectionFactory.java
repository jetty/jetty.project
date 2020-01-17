//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslClientConnectionFactory implements ClientConnectionFactory
{
    public static final String SSL_CONTEXT_FACTORY_CONTEXT_KEY = "ssl.context.factory";
    public static final String SSL_PEER_HOST_CONTEXT_KEY = "ssl.peer.host";
    public static final String SSL_PEER_PORT_CONTEXT_KEY = "ssl.peer.port";
    public static final String SSL_ENGINE_CONTEXT_KEY = "ssl.engine";

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
     * @return whether is not required that peers send the TLS {@code close_notify} message
     * @deprecated use {@link #isRequireCloseMessage()} instead
     */
    @Deprecated
    public boolean isAllowMissingCloseMessage()
    {
        return !isRequireCloseMessage();
    }

    /**
     * @param allowMissingCloseMessage whether is not required that peers send the TLS {@code close_notify} message
     * @deprecated use {@link #setRequireCloseMessage(boolean)} instead
     */
    @Deprecated
    public void setAllowMissingCloseMessage(boolean allowMissingCloseMessage)
    {
        setRequireCloseMessage(!allowMissingCloseMessage);
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
        String host = (String)context.get(SSL_PEER_HOST_CONTEXT_KEY);
        int port = (Integer)context.get(SSL_PEER_PORT_CONTEXT_KEY);
        SSLEngine engine = sslContextFactory.newSSLEngine(host, port);
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
            ContainerLifeCycle connector = (ContainerLifeCycle)context.get(ClientConnectionFactory.CONNECTOR_CONTEXT_KEY);
            connector.getBeans(SslHandshakeListener.class).forEach(sslConnection::addHandshakeListener);
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
                String host = (String)context.get(SSL_PEER_HOST_CONTEXT_KEY);
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
