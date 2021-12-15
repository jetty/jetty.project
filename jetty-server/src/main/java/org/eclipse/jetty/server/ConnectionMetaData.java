//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.SocketAddress;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Attributes;

public interface ConnectionMetaData extends Attributes
{
    String getId();

    HttpVersion getVersion();

    String getProtocol();

    Connection getConnection();

    Connector getConnector();

    boolean isPersistent();

    boolean isSecure();

    SocketAddress getRemote();

    SocketAddress getLocal();

    class Wrapper extends Attributes.Wrapper implements ConnectionMetaData
    {
        private final ConnectionMetaData _wrapped;

        public Wrapper(ConnectionMetaData wrapped)
        {
            super(wrapped);
            _wrapped = wrapped;
        }

        @Override
        public String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public HttpVersion getVersion()
        {
            return _wrapped.getVersion();
        }

        @Override
        public String getProtocol()
        {
            return _wrapped.getProtocol();
        }

        // TODO we should not need this
        @Override
        @Deprecated
        public Connection getConnection()
        {
            return _wrapped.getConnection();
        }

        // TODO we should not need this. Currently used only for X509 cert access
        @Override
        @Deprecated
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
        public SocketAddress getRemote()
        {
            return _wrapped.getRemote();
        }

        @Override
        public SocketAddress getLocal()
        {
            return _wrapped.getLocal();
        }
    }
}
