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

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SslConnectionFactory extends AbstractConnectionFactory implements ConnectionFactory.Detecting, ConnectionFactory.Configuring
{
    private static final int TLS_ALERT_FRAME_TYPE = 0x15;
    private static final int TLS_HANDSHAKE_FRAME_TYPE = 0x16;
    private static final int TLS_MAJOR_VERSION = 3;

    private final SslContextFactory.Server _sslContextFactory;
    private final String _nextProtocol;
    private boolean _directBuffersForEncryption = false;
    private boolean _directBuffersForDecryption = false;
    private boolean _ensureSecureRequestCustomizer = true;

    public SslConnectionFactory()
    {
        this(HttpVersion.HTTP_1_1.asString());
    }

    public SslConnectionFactory(@Name("next") String nextProtocol)
    {
        this(null, nextProtocol);
    }

    public SslConnectionFactory(@Name("sslContextFactory") SslContextFactory.Server factory, @Name("next") String nextProtocol)
    {
        super("SSL");
        _sslContextFactory = factory == null ? new SslContextFactory.Server() : factory;
        _nextProtocol = nextProtocol;
        addBean(_sslContextFactory);
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return _sslContextFactory;
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

    public String getNextProtocol()
    {
        return _nextProtocol;
    }

    public boolean isEnsureSecureRequestCustomizer()
    {
        return _ensureSecureRequestCustomizer;
    }

    /**
     * @param ensureSecureRequestCustomizer True if this factory ensures that all {@link HttpConfiguration}s on
     * associated {@link Connector}s have an {@link SecureRequestCustomizer} instance.
     * @see Configuring
     */
    public void setEnsureSecureRequestCustomizer(boolean ensureSecureRequestCustomizer)
    {
        _ensureSecureRequestCustomizer = ensureSecureRequestCustomizer;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        SSLEngine engine = _sslContextFactory.newSSLEngine();
        engine.setUseClientMode(false);
        SSLSession session = engine.getSession();

        if (session.getPacketBufferSize() > getInputBufferSize())
            setInputBufferSize(session.getPacketBufferSize());
    }

    @Override
    public void configure(Connector connector)
    {
        if (isEnsureSecureRequestCustomizer())
        {
            connector.getContainedBeans(HttpConfiguration.class).forEach(configuration ->
            {
                if (configuration.getCustomizer(SecureRequestCustomizer.class) == null)
                    configuration.addCustomizer(new SecureRequestCustomizer());
            });
        }
    }

    @Override
    public Detection detect(ByteBuffer buffer)
    {
        if (buffer.remaining() < 2)
            return Detection.NEED_MORE_BYTES;
        int tlsFrameType = buffer.get(0) & 0xFF;
        int tlsMajorVersion = buffer.get(1) & 0xFF;
        boolean seemsSsl = (tlsFrameType == TLS_HANDSHAKE_FRAME_TYPE || tlsFrameType == TLS_ALERT_FRAME_TYPE) && tlsMajorVersion == TLS_MAJOR_VERSION;
        return seemsSsl ? Detection.RECOGNIZED : Detection.NOT_RECOGNIZED;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        SocketAddress remoteSocketAddress = endPoint.getRemoteSocketAddress();
        SSLEngine engine = remoteSocketAddress instanceof InetSocketAddress
            ? _sslContextFactory.newSSLEngine((InetSocketAddress)remoteSocketAddress)
            : _sslContextFactory.newSSLEngine();
        engine.setUseClientMode(false);

        SslConnection sslConnection = newSslConnection(connector, endPoint, engine);
        sslConnection.setRenegotiationAllowed(_sslContextFactory.isRenegotiationAllowed());
        sslConnection.setRenegotiationLimit(_sslContextFactory.getRenegotiationLimit());
        configure(sslConnection, connector, endPoint);

        ConnectionFactory next = connector.getConnectionFactory(_nextProtocol);
        EndPoint decryptedEndPoint = sslConnection.getDecryptedEndPoint();
        Connection connection = next.newConnection(connector, decryptedEndPoint);
        decryptedEndPoint.setConnection(connection);

        return sslConnection;
    }

    protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
    {
        ByteBufferPool byteBufferPool = connector.getByteBufferPool();
        RetainableByteBufferPool retainableByteBufferPool = RetainableByteBufferPool.findOrAdapt(connector, byteBufferPool);
        return new SslConnection(retainableByteBufferPool, byteBufferPool, connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption());
    }

    @Override
    protected AbstractConnection configure(AbstractConnection connection, Connector connector, EndPoint endPoint)
    {
        if (connection instanceof SslConnection)
        {
            SslConnection sslConnection = (SslConnection)connection;
            if (connector instanceof ContainerLifeCycle)
            {
                ContainerLifeCycle container = (ContainerLifeCycle)connector;
                container.getBeans(SslHandshakeListener.class).forEach(sslConnection::addHandshakeListener);
            }
            getBeans(SslHandshakeListener.class).forEach(sslConnection::addHandshakeListener);
        }
        return super.configure(connection, connector, endPoint);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s->%s}", this.getClass().getSimpleName(), hashCode(), getProtocol(), _nextProtocol);
    }
}
