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

import java.net.Socket;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Connection.Listener;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;

/**
 * A Connection Lister for customization of SocketConnections.
 * <p>
 * Instances of this listener may be added to a {@link Connector} (or
 * {@link ConnectionFactory}) so that they are applied to all connections
 * for that connector (or protocol) and thus allow additional Socket
 * configuration to be applied by implementing {@link #customize(Socket, Class, boolean)}
 */
public class SocketCustomizationListener implements Listener
{
    private final boolean _ssl;

    /**
     * Construct with SSL unwrapping on.
     */
    public SocketCustomizationListener()
    {
        this(true);
    }

    /**
     * @param ssl If True, then a Socket underlying an SSLConnection is unwrapped
     * and notified.
     */
    public SocketCustomizationListener(boolean ssl)
    {
        _ssl = ssl;
    }

    @Override
    public void onOpened(Connection connection)
    {
        EndPoint endp = connection.getEndPoint();
        boolean ssl = false;

        if (_ssl && endp instanceof DecryptedEndPoint)
        {
            endp = ((DecryptedEndPoint)endp).getSslConnection().getEndPoint();
            ssl = true;
        }

        if (endp instanceof SocketChannelEndPoint)
        {
            Socket socket = ((SocketChannelEndPoint)endp).getSocket();
            customize(socket, connection.getClass(), ssl);
        }
    }

    /**
     * This method may be extended to configure a socket on open
     * events.
     *
     * @param socket The Socket to configure
     * @param connection The class of the connection (The socket may be wrapped
     * by an {@link SslConnection} prior to this connection).
     * @param ssl True if the socket is wrapped with an SslConnection
     */
    protected void customize(Socket socket, Class<? extends Connection> connection, boolean ssl)
    {
    }

    @Override
    public void onClosed(Connection connection)
    {
    }
}
