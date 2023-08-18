//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.HostPort;

public interface ConnectionMetaData extends Attributes
{
    /**
     * @return a unique (within the lifetime of the JVM) identifier string for the network connection to the JVM
     */
    String getId();

    HttpConfiguration getHttpConfiguration();

    HttpVersion getHttpVersion();

    String getProtocol();

    // TODO should this be only here or only on HttpChannel, should not be on both.
    Connection getConnection();

    // TODO should this be only here or only on HttpChannel, should not be on both.
    //      Currently mostly used to get stuff like ByteBufferPool and Scheduler - maybe provide those directly?
    Connector getConnector();

    boolean isPersistent();

    boolean isSecure();

    /**
     * @return whether the functionality of pushing resources is supported
     */
    default boolean isPushSupported()
    {
        return false;
    }

    /**
     * @return The address of the remote end of this connection.  By default, this is the first hop of the underlying
     *         network connection, but it may be wrapped to represent a more remote end point.
     */
    SocketAddress getRemoteSocketAddress();

    /**
     * @return The address of the local end of this connection. By default, this is the address of the underlying
     *         network connection, but it may be wrapped if the deployment wishes to hide all local details.
     */
    SocketAddress getLocalSocketAddress();

    /**
     * @return The URI authority that this server represents. By default, this is the address of the network socket on
     *         which the connection was accepted, but it may be configured to a specific address.
     * @see HttpConfiguration#setServerAuthority(HostPort)
     */
    default HostPort getServerAuthority()
    {
        HttpConfiguration httpConfiguration = getHttpConfiguration();
        HostPort authority = httpConfiguration.getServerAuthority();
        if (authority != null)
            return authority;

        SocketAddress localSocketAddress = getLocalSocketAddress();
        if (localSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return new HostPort(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
        else if (localSocketAddress != null)
            return new HostPort(localSocketAddress.toString());
        return null;
    }

    class Wrapper extends Attributes.Wrapper implements ConnectionMetaData
    {
        public Wrapper(ConnectionMetaData wrapped)
        {
            super(wrapped);
        }

        @Override
        public ConnectionMetaData getWrapped()
        {
            return (ConnectionMetaData)super.getWrapped();
        }

        @Override
        public String getId()
        {
            return getWrapped().getId();
        }

        @Override
        public HttpConfiguration getHttpConfiguration()
        {
            return getWrapped().getHttpConfiguration();
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return getWrapped().getHttpVersion();
        }

        @Override
        public String getProtocol()
        {
            return getWrapped().getProtocol();
        }

        @Override
        public Connection getConnection()
        {
            return getWrapped().getConnection();
        }

        @Override
        public Connector getConnector()
        {
            return getWrapped().getConnector();
        }

        @Override
        public boolean isPersistent()
        {
            return getWrapped().isPersistent();
        }

        @Override
        public boolean isSecure()
        {
            return getWrapped().isSecure();
        }

        @Override
        public boolean isPushSupported()
        {
            return getWrapped().isPushSupported();
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return getWrapped().getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return getWrapped().getLocalSocketAddress();
        }

        @Override
        public HostPort getServerAuthority()
        {
            return getWrapped().getServerAuthority();
        }
    }
}
