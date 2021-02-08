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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData.Request;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP2 Clear Text Connection factory.
 * <p>This extension of HTTP2ServerConnection Factory sets the
 * protocol name to "h2c" as used by the clear text upgrade mechanism
 * for HTTP2 and marks all TLS ciphers as unacceptable.
 * </p>
 * <p>If used in combination with a {@link HttpConnectionFactory} as the
 * default protocol, this factory can support the non-standard direct
 * update mechanism, where an HTTP1 request of the form "PRI * HTTP/2.0"
 * is used to trigger a switch to an HTTP2 connection.    This approach
 * allows a single port to accept either HTTP/1 or HTTP/2 direct
 * connections.
 */
public class HTTP2CServerConnectionFactory extends HTTP2ServerConnectionFactory implements ConnectionFactory.Upgrading
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2CServerConnectionFactory.class);

    public HTTP2CServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        this(httpConfiguration, "h2c");
    }

    public HTTP2CServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration, @Name("protocols") String... protocols)
    {
        super(httpConfiguration, protocols);
        for (String p : protocols)
        {
            if (!HTTP2ServerConnection.isSupportedProtocol(p))
                throw new IllegalArgumentException("Unsupported HTTP2 Protocol variant: " + p);
        }
    }

    @Override
    public boolean isAcceptable(String protocol, String tlsProtocol, String tlsCipher)
    {
        // Never use TLS with h2c
        return false;
    }

    @Override
    public Connection upgradeConnection(Connector connector, EndPoint endPoint, Request request, HttpFields.Mutable response101) throws BadMessageException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} upgrading {}{}{}", this, request, System.lineSeparator(), request.getFields());

        if (request.getContentLength() > 0)
            return null;

        HTTP2ServerConnection connection = (HTTP2ServerConnection)newConnection(connector, endPoint);
        if (connection.upgrade(request, response101))
            return connection;
        return null;
    }
}
