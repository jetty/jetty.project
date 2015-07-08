//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.util.Objects;

import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.annotation.Name;

public abstract class AbstractHTTP2ServerConnectionFactory extends AbstractConnectionFactory
{
    private int maxDynamicTableSize = 4096;
    private int initialStreamSendWindow = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
    private int maxConcurrentStreams = -1;
    private int maxHeaderBlockFragment = 0;
    private final HttpConfiguration httpConfiguration;

    public AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        this(httpConfiguration,"h2","h2-17","h2-16","h2-15","h2-14");
    }

    protected AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration, String... protocols)
    {
        super(protocols);
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
    }

    public int getMaxDynamicTableSize()
    {
        return maxDynamicTableSize;
    }

    public void setMaxDynamicTableSize(int maxDynamicTableSize)
    {
        this.maxDynamicTableSize = maxDynamicTableSize;
    }

    public int getInitialStreamSendWindow()
    {
        return initialStreamSendWindow;
    }

    public void setInitialStreamSendWindow(int initialStreamSendWindow)
    {
        this.initialStreamSendWindow = initialStreamSendWindow;
    }

    public int getMaxConcurrentStreams()
    {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(int maxConcurrentStreams)
    {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public int getMaxHeaderBlockFragment()
    {
        return maxHeaderBlockFragment;
    }

    public void setMaxHeaderBlockFragment(int maxHeaderBlockFragment)
    {
        this.maxHeaderBlockFragment = maxHeaderBlockFragment;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerSessionListener listener = newSessionListener(connector, endPoint);

        Generator generator = new Generator(connector.getByteBufferPool(), getMaxDynamicTableSize(), getMaxHeaderBlockFragment());
        FlowControlStrategy flowControl = newFlowControlStrategy();
        HTTP2ServerSession session = new HTTP2ServerSession(connector.getScheduler(), endPoint, generator, listener, flowControl);
        session.setMaxLocalStreams(getMaxConcurrentStreams());
        session.setMaxRemoteStreams(getMaxConcurrentStreams());
        // For a single stream in a connection, there will be a race between
        // the stream idle timeout and the connection idle timeout. However,
        // the typical case is that the connection will be busier and the
        // stream idle timeout will expire earlier that the connection's.
        session.setStreamIdleTimeout(endPoint.getIdleTimeout());

        ServerParser parser = newServerParser(connector, session);
        HTTP2Connection connection = new HTTP2ServerConnection(connector.getByteBufferPool(), connector.getExecutor(),
                        endPoint, httpConfiguration, parser, session, getInputBufferSize(), listener);

        return configure(connection, connector, endPoint);
    }

    protected FlowControlStrategy newFlowControlStrategy()
    {
        return new BufferingFlowControlStrategy(getInitialStreamSendWindow(), 0.5F);
    }

    protected abstract ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint);

    protected ServerParser newServerParser(Connector connector, ServerParser.Listener listener)
    {
        return new ServerParser(connector.getByteBufferPool(), listener, getMaxDynamicTableSize(), getHttpConfiguration().getRequestHeaderSize());
    }
}
