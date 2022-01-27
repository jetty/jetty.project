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
        EndPoint endPoint = connection.getEndPoint();
        boolean ssl = false;

        if (_ssl && endPoint instanceof DecryptedEndPoint)
        {
            endPoint = ((DecryptedEndPoint)endPoint).getSslConnection().getEndPoint();
            ssl = true;
        }

        if (endPoint instanceof SocketChannelEndPoint)
        {
            Socket socket = ((SocketChannelEndPoint)endPoint).getChannel().socket();
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
