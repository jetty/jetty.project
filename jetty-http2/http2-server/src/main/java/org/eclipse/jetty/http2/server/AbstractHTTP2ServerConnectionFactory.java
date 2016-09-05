//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.LifeCycle;

@ManagedObject
public abstract class AbstractHTTP2ServerConnectionFactory extends AbstractConnectionFactory
{
    private final Connection.Listener connectionListener = new ConnectionListener();
    private final HttpConfiguration httpConfiguration;
    private int maxDynamicTableSize = 4096;
    private int initialStreamRecvWindow = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
    private int initialSessionRecvWindow = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
    private int maxConcurrentStreams = 128;
    private int maxHeaderBlockFragment = 0;
    private FlowControlStrategy.Factory flowControlStrategyFactory = () -> new BufferingFlowControlStrategy(0.5F);
    private long streamIdleTimeout;

    public AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        this(httpConfiguration,"h2");
    }

    protected AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration, String... protocols)
    {
        super(protocols);
        for (String p:protocols)
            if (!HTTP2ServerConnection.isSupportedProtocol(p))
                throw new IllegalArgumentException("Unsupported HTTP2 Protocol variant: "+p);
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        addBean(httpConfiguration);
    }

    @ManagedAttribute("The HPACK dynamic table maximum size")
    public int getMaxDynamicTableSize()
    {
        return maxDynamicTableSize;
    }

    public void setMaxDynamicTableSize(int maxDynamicTableSize)
    {
        this.maxDynamicTableSize = maxDynamicTableSize;
    }

    @ManagedAttribute("The initial size of session's flow control receive window")
    public int getInitialSessionRecvWindow()
    {
        return initialSessionRecvWindow;
    }

    public void setInitialSessionRecvWindow(int initialSessionRecvWindow)
    {
        this.initialSessionRecvWindow = initialSessionRecvWindow;
    }

    @ManagedAttribute("The initial size of stream's flow control receive window")
    public int getInitialStreamRecvWindow()
    {
        return initialStreamRecvWindow;
    }

    public void setInitialStreamRecvWindow(int initialStreamRecvWindow)
    {
        this.initialStreamRecvWindow = initialStreamRecvWindow;
    }

    @ManagedAttribute("The max number of concurrent streams per session")
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

    public FlowControlStrategy.Factory getFlowControlStrategyFactory()
    {
        return flowControlStrategyFactory;
    }

    public void setFlowControlStrategyFactory(FlowControlStrategy.Factory flowControlStrategyFactory)
    {
        this.flowControlStrategyFactory = flowControlStrategyFactory;
    }

    @ManagedAttribute("The stream idle timeout in milliseconds")
    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
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
        FlowControlStrategy flowControl = getFlowControlStrategyFactory().newFlowControlStrategy();
        HTTP2ServerSession session = new HTTP2ServerSession(connector.getScheduler(), endPoint, generator, listener, flowControl);
        session.setMaxLocalStreams(getMaxConcurrentStreams());
        session.setMaxRemoteStreams(getMaxConcurrentStreams());
        // For a single stream in a connection, there will be a race between
        // the stream idle timeout and the connection idle timeout. However,
        // the typical case is that the connection will be busier and the
        // stream idle timeout will expire earlier than the connection's.
        long streamIdleTimeout = getStreamIdleTimeout();
        if (streamIdleTimeout <= 0)
            streamIdleTimeout = endPoint.getIdleTimeout();
        session.setStreamIdleTimeout(streamIdleTimeout);
        session.setInitialSessionRecvWindow(getInitialSessionRecvWindow());

        ServerParser parser = newServerParser(connector, session);
        HTTP2Connection connection = new HTTP2ServerConnection(connector.getByteBufferPool(), connector.getExecutor(),
                        endPoint, httpConfiguration, parser, session, getInputBufferSize(), listener);
        connection.addListener(connectionListener);
        return configure(connection, connector, endPoint);
    }

    protected abstract ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint);

    protected ServerParser newServerParser(Connector connector, ServerParser.Listener listener)
    {
        return new ServerParser(connector.getByteBufferPool(), listener, getMaxDynamicTableSize(), getHttpConfiguration().getRequestHeaderSize());
    }

    private class ConnectionListener implements Connection.Listener
    {
        @Override
        public void onOpened(Connection connection)
        {
            addManaged((LifeCycle)((HTTP2Connection)connection).getSession());
        }

        @Override
        public void onClosed(Connection connection)
        {
            removeBean(((HTTP2Connection)connection).getSession());
        }
    }
}
