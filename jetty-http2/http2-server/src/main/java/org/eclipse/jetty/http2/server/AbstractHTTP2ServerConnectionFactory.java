//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2FlowControl;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;

public abstract class AbstractHTTP2ServerConnectionFactory extends AbstractConnectionFactory
{
    private int headerTableSize = 4096;
    private int initialWindowSize = 65535;

    public AbstractHTTP2ServerConnectionFactory()
    {
        super("h2-12");
    }

    public int getHeaderTableSize()
    {
        return headerTableSize;
    }

    public void setHeaderTableSize(int headerTableSize)
    {
        this.headerTableSize = headerTableSize;
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        Session.Listener listener = newSessionListener(connector, endPoint);

        Generator generator = new Generator(connector.getByteBufferPool(), getHeaderTableSize());
        HTTP2ServerSession session = new HTTP2ServerSession(endPoint, generator, listener,
                new HTTP2FlowControl(getInitialWindowSize()));

        Parser parser = new ServerParser(connector.getByteBufferPool(), session);
        HTTP2Connection connection = new HTTP2Connection(connector.getByteBufferPool(), connector.getExecutor(),
                endPoint, parser, getInputBufferSize());

        return configure(connection, connector, endPoint);
    }

    protected abstract Session.Listener newSessionListener(Connector connector, EndPoint endPoint);
}
