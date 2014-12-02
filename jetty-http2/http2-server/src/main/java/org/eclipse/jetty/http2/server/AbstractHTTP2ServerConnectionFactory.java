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

import org.eclipse.jetty.http2.FlowControl;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2FlowControl;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;

public abstract class AbstractHTTP2ServerConnectionFactory extends AbstractConnectionFactory
{
    private boolean dispatchIO = true;
    private int maxHeaderTableSize = 4096;
    private int initialStreamWindow = FlowControl.DEFAULT_WINDOW_SIZE;
    private int maxConcurrentStreams = -1;

    public AbstractHTTP2ServerConnectionFactory()
    {
        super("h2-15","h2-14");
    }

    public boolean isDispatchIO()
    {
        return dispatchIO;
    }

    public void setDispatchIO(boolean dispatchIO)
    {
        this.dispatchIO = dispatchIO;
    }

    public int getMaxHeaderTableSize()
    {
        return maxHeaderTableSize;
    }

    public void setMaxHeaderTableSize(int maxHeaderTableSize)
    {
        this.maxHeaderTableSize = maxHeaderTableSize;
    }

    public int getInitialStreamWindow()
    {
        return initialStreamWindow;
    }

    public void setInitialStreamWindow(int initialStreamWindow)
    {
        this.initialStreamWindow = initialStreamWindow;
    }

    public int getMaxConcurrentStreams()
    {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(int maxConcurrentStreams)
    {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerSessionListener listener = newSessionListener(connector, endPoint);

        Generator generator = new Generator(connector.getByteBufferPool(), getMaxHeaderTableSize());
        HTTP2ServerSession session = new HTTP2ServerSession(connector.getScheduler(), endPoint, generator, listener,
                new HTTP2FlowControl(getInitialStreamWindow()));
        session.setMaxLocalStreams(getMaxConcurrentStreams());
        session.setMaxRemoteStreams(getMaxConcurrentStreams());
        long idleTimeout = endPoint.getIdleTimeout();
        if (idleTimeout > 0)
            idleTimeout /= 2;
        session.setStreamIdleTimeout(idleTimeout);

        Parser parser = newServerParser(connector.getByteBufferPool(), session);
        HTTP2Connection connection = new HTTP2ServerConnection(connector.getByteBufferPool(), connector.getExecutor(),
                        endPoint, parser, session, getInputBufferSize(), isDispatchIO(), listener);

        return configure(connection, connector, endPoint);
    }

    protected abstract ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint);

    protected abstract ServerParser newServerParser(ByteBufferPool byteBufferPool, ServerParser.Listener listener);
}
