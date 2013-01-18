//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server;

import java.io.IOException;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NextProtoNegoServerConnection extends AbstractConnection implements NextProtoNego.ServerProvider
{
    private final Logger LOG = Log.getLogger(getClass());
    private final Connector connector;
    private final SSLEngine engine;
    private final List<String> protocols;
    private final String defaultProtocol;
    private String nextProtocol; // No need to be volatile: it is modified and read by the same thread

    public NextProtoNegoServerConnection(EndPoint endPoint, SSLEngine engine, Connector connector, List<String>protocols, String defaultProtocol)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.protocols = protocols;
        this.defaultProtocol = defaultProtocol;
        this.engine = engine;
        NextProtoNego.put(engine, this);
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
        while (true)
        {
            int filled = fill();
            if (filled == 0 && nextProtocol == null)
                fillInterested();
            if (filled <= 0 || nextProtocol != null)
                break;
        }

        if (nextProtocol == null && engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
        {
            // The client sent the NPN extension, but did not send the NextProtocol
            // message with the chosen protocol so we need to close
            LOG.debug("{} missing next protocol. SSLEngine: {}", this, engine);
            close();
        }

        if (nextProtocol != null)
        {
            ConnectionFactory connectionFactory = connector.getConnectionFactory(nextProtocol);
            EndPoint endPoint = getEndPoint();
            Connection oldConnection = endPoint.getConnection();
            oldConnection.onClose();
            Connection connection = connectionFactory.newConnection(connector, endPoint);
            LOG.debug("{} switching from {} to {}", this, oldConnection, connection);
            endPoint.setConnection(connection);
            getEndPoint().getConnection().onOpen();
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
            NextProtoNego.remove(engine);
            getEndPoint().close();
            return -1;
        }
    }

    @Override
    public void unsupported()
    {
        protocolSelected(defaultProtocol);
    }

    @Override
    public List<String> protocols()
    {
        return protocols;
    }

    @Override
    public void protocolSelected(String protocol)
    {
        LOG.debug("{} protocol selected {}", this, protocol);
        nextProtocol = protocol != null ? protocol : defaultProtocol;
        NextProtoNego.remove(engine);
    }
}
