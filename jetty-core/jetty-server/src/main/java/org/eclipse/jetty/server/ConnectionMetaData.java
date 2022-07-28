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
     *         which the connection was accepted, but it may be wrapped to represent a virtual address.
     */
    HostPort getServerAuthority();

    static HostPort getServerAuthority(HttpConfiguration httpConfiguration, ConnectionMetaData connectionMetaData)
    {
        HostPort authority = httpConfiguration.getServerAuthority();
        if (authority != null)
            return authority;

        SocketAddress local = connectionMetaData.getLocalSocketAddress();
        if (local instanceof InetSocketAddress inet)
            return new HostPort(inet.getHostString(), inet.getPort());

        return null;
    }

    class Wrapper extends Attributes.Wrapper implements ConnectionMetaData
    {
        private final ConnectionMetaData _wrapped;

        public Wrapper(ConnectionMetaData wrapped)
        {
            super(wrapped);
            _wrapped = wrapped;
        }

        protected ConnectionMetaData getWrappedConnectionMetaData()
        {
            return _wrapped;
        }

        @Override
        public String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public HttpConfiguration getHttpConfiguration()
        {
            return _wrapped.getHttpConfiguration();
        }

        @Override
        public HttpVersion getHttpVersion()
        {
            return _wrapped.getHttpVersion();
        }

        @Override
        public String getProtocol()
        {
            return _wrapped.getProtocol();
        }

        @Override
        public Connection getConnection()
        {
            return _wrapped.getConnection();
        }

        @Override
        public Connector getConnector()
        {
            return _wrapped.getConnector();
        }

        @Override
        public boolean isPersistent()
        {
            return _wrapped.isPersistent();
        }

        @Override
        public boolean isSecure()
        {
            return _wrapped.isSecure();
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return _wrapped.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return _wrapped.getLocalSocketAddress();
        }

        @Override
        public HostPort getServerAuthority()
        {
            return _wrapped.getServerAuthority();
        }
    }
}
