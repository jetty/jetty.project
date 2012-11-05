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

package org.eclipse.jetty.spdy.client;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class NextProtoNegoClientConnection extends AbstractConnection implements NextProtoNego.ClientProvider
{
    private final Logger LOG = Log.getLogger(getClass());
    private final SocketChannel channel;
    private final Object attachment;
    private final SPDYClient client;
    private final SSLEngine engine;
    private volatile boolean completed;

    public NextProtoNegoClientConnection(SocketChannel channel, DecryptedEndPoint endPoint, Object attachment, Executor executor, SPDYClient client)
    {
        super(endPoint, executor);
        this.channel = channel;
        this.attachment = attachment;
        this.client = client;
        this.engine = endPoint.getSslConnection().getSSLEngine();
        NextProtoNego.put(engine, this);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        try
        {
            getEndPoint().flush(BufferUtil.EMPTY_BUFFER);
            if (completed)
                replaceConnection();
            else
                fillInterested();
        }
        catch(IOException e)
        {
            throw new RuntimeIOException(e);
        }
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
            replaceConnection();
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
    public boolean supports()
    {
        return true;
    }

    @Override
    public void unsupported()
    {
        NextProtoNego.remove(engine);
        completed = true;
    }

    @Override
    public String selectProtocol(List<String> protocols)
    {
        NextProtoNego.remove(engine);
        completed = true;
        String protocol = client.selectProtocol(protocols);
        return protocol == null ? null : protocol;
    }

    private void replaceConnection()
    {
        EndPoint endPoint = getEndPoint();
        Connection connection = client.getConnectionFactory().newConnection(channel, endPoint, attachment);
        endPoint.getConnection().onClose();
        endPoint.setConnection(connection);
        connection.onOpen();
    }
}
