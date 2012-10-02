//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
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
    private boolean completed; // No need to be volatile: it is modified and read by the same thread


    public NextProtoNegoServerConnection(DecryptedEndPoint endPoint, Connector connector, List<String>protocols, String defaultProtocol)
    {
        super(endPoint, connector.getExecutor());
        this.connector = connector;
        this.protocols = protocols;
        this.defaultProtocol=defaultProtocol;
        engine = endPoint.getSslConnection().getSSLEngine();

        NextProtoNego.put(engine,this);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onClose()
    {
        super.onClose();
    }

    @Override
    public void onFillable()
    {
        while (true)
        {
            int filled = fill();
            if (filled == 0 && !completed)
                fillInterested();
            if (filled <= 0 || completed)
                break;
        }
        
        if (completed)
            getEndPoint().getConnection().onOpen();
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
        LOG.debug("{} protocolSelected {}",this,protocol);
        NextProtoNego.remove(engine);
        ConnectionFactory connectionFactory = connector.getConnectionFactory(protocol);
        EndPoint endPoint = getEndPoint();
        endPoint.getConnection().onClose();
        Connection connection = connectionFactory.newConnection(connector, endPoint);
        endPoint.setConnection(connection);
        completed = true;
    }
}
