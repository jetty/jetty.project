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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class NegotiatingServerConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(NegotiatingServerConnection.class);

    public interface CipherDiscriminator
    {
        boolean isAcceptable(String protocol, String tlsProtocol, String tlsCipher);
    }

    private final Connector connector;
    private final SSLEngine engine;
    private final List<String> protocols;
    private final String defaultProtocol;
    private String protocol; // No need to be volatile: it is modified and read by the same thread

    protected NegotiatingServerConnection(Connector connector, EndPoint endPoint, SSLEngine engine, List<String> protocols, String defaultProtocol)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.protocols = protocols;
        this.defaultProtocol = defaultProtocol;
        this.engine = engine;
    }

    public List<String> getProtocols()
    {
        return protocols;
    }

    public String getDefaultProtocol()
    {
        return defaultProtocol;
    }

    public Connector getConnector()
    {
        return connector;
    }

    public SSLEngine getSSLEngine()
    {
        return engine;
    }

    public String getProtocol()
    {
        return protocol;
    }

    protected void setProtocol(String protocol)
    {
        this.protocol = protocol;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onFillable()
    {
        int filled = fill();

        if (filled == 0)
        {
            if (protocol == null)
            {
                if (engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                {
                    // Here the SSL handshake is finished, but the protocol has not been negotiated.
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} could not negotiate protocol, SSLEngine: {}", this, engine);
                    close();
                }
                else
                {
                    // Here the SSL handshake is not finished yet but we filled 0 bytes,
                    // so we need to read more.
                    fillInterested();
                }
            }
            else
            {
                ConnectionFactory connectionFactory = connector.getConnectionFactory(protocol);
                if (connectionFactory == null)
                {
                    LOG.info("{} application selected protocol '{}', but no correspondent {} has been configured",
                        this, protocol, ConnectionFactory.class.getName());
                    close();
                }
                else
                {
                    EndPoint endPoint = getEndPoint();
                    Connection newConnection = connectionFactory.newConnection(connector, endPoint);
                    endPoint.upgrade(newConnection);
                }
            }
        }
        else if (filled < 0)
        {
            // Something went bad, we need to close.
            if (LOG.isDebugEnabled())
                LOG.debug("{} detected close on client side", this);
            close();
        }
        else
        {
            // Must never happen, since we fill using an empty buffer
            throw new IllegalStateException();
        }
    }

    private int fill()
    {
        try
        {
            return getEndPoint().fill(BufferUtil.EMPTY_BUFFER);
        }
        catch (IOException x)
        {
            LOG.debug(x);
            close();
            return -1;
        }
    }

    @Override
    public void close()
    {
        // Gentler close for SSL.
        getEndPoint().shutdownOutput();
        super.close();
    }
}
