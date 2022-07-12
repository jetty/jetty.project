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

package org.eclipse.jetty.http2.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.RateControl;
import org.eclipse.jetty.http2.WindowRateControl;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.internal.HTTP2Connection;
import org.eclipse.jetty.http2.internal.generator.Generator;
import org.eclipse.jetty.http2.internal.parser.ServerParser;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerConnection;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerSession;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;

@ManagedObject
public abstract class AbstractHTTP2ServerConnectionFactory extends AbstractConnectionFactory
{
    private static boolean isProtocolSupported(String protocol)
    {
        return switch (protocol)
        {
            case "h2", "h2c" -> true;
            default -> false;
        };
    }

    private final HTTP2SessionContainer sessionContainer = new HTTP2SessionContainer();
    private final HttpConfiguration httpConfiguration;
    private int maxDynamicTableSize = 4096;
    private int initialSessionRecvWindow = 1024 * 1024;
    private int initialStreamRecvWindow = 512 * 1024;
    private int maxConcurrentStreams = 128;
    private int maxHeaderBlockFragment = 0;
    private int maxFrameLength = Frame.DEFAULT_MAX_LENGTH;
    private int maxSettingsKeys = SettingsFrame.DEFAULT_MAX_KEYS;
    private boolean connectProtocolEnabled = true;
    private RateControl.Factory rateControlFactory = new WindowRateControl.Factory(50);
    private FlowControlStrategy.Factory flowControlStrategyFactory = () -> new BufferingFlowControlStrategy(0.5F);
    private long streamIdleTimeout;
    private boolean useInputDirectByteBuffers;
    private boolean useOutputDirectByteBuffers;

    public AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration)
    {
        this(httpConfiguration, "h2");
    }

    protected AbstractHTTP2ServerConnectionFactory(@Name("config") HttpConfiguration httpConfiguration, @Name("protocols") String... protocols)
    {
        super(protocols);
        for (String p : protocols)
        {
            if (!isProtocolSupported(p))
                throw new IllegalArgumentException("Unsupported HTTP2 Protocol variant: " + p);
        }
        addBean(sessionContainer);
        this.httpConfiguration = Objects.requireNonNull(httpConfiguration);
        addBean(httpConfiguration);
        setInputBufferSize(Frame.DEFAULT_MAX_LENGTH + Frame.HEADER_LENGTH);
        setUseInputDirectByteBuffers(httpConfiguration.isUseInputDirectByteBuffers());
        setUseOutputDirectByteBuffers(httpConfiguration.isUseOutputDirectByteBuffers());
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

    @ManagedAttribute("The max header block fragment")
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

    @ManagedAttribute("The max frame length in bytes")
    public int getMaxFrameLength()
    {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength)
    {
        this.maxFrameLength = maxFrameLength;
    }

    @ManagedAttribute("The max number of keys in all SETTINGS frames")
    public int getMaxSettingsKeys()
    {
        return maxSettingsKeys;
    }

    public void setMaxSettingsKeys(int maxSettingsKeys)
    {
        this.maxSettingsKeys = maxSettingsKeys;
    }

    @ManagedAttribute("Whether CONNECT requests supports a protocol")
    public boolean isConnectProtocolEnabled()
    {
        return connectProtocolEnabled;
    }

    public void setConnectProtocolEnabled(boolean connectProtocolEnabled)
    {
        this.connectProtocolEnabled = connectProtocolEnabled;
    }

    /**
     * @return the factory that creates RateControl objects
     */
    public RateControl.Factory getRateControlFactory()
    {
        return rateControlFactory;
    }

    /**
     * <p>Sets the factory that creates a per-connection RateControl object.</p>
     *
     * @param rateControlFactory the factory that creates RateControl objects
     */
    public void setRateControlFactory(RateControl.Factory rateControlFactory)
    {
        this.rateControlFactory = Objects.requireNonNull(rateControlFactory);
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for reading")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct ByteBuffers for writing")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return httpConfiguration;
    }

    protected Map<Integer, Integer> newSettings()
    {
        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.HEADER_TABLE_SIZE, getMaxDynamicTableSize());
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, getInitialStreamRecvWindow());
        int maxConcurrentStreams = getMaxConcurrentStreams();
        if (maxConcurrentStreams >= 0)
            settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
        settings.put(SettingsFrame.MAX_HEADER_LIST_SIZE, getHttpConfiguration().getRequestHeaderSize());
        settings.put(SettingsFrame.ENABLE_CONNECT_PROTOCOL, isConnectProtocolEnabled() ? 1 : 0);
        return settings;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        ServerSessionListener listener = newSessionListener(connector, endPoint);

        Generator generator = new Generator(connector.getByteBufferPool(), isUseOutputDirectByteBuffers(), getMaxDynamicTableSize(), getMaxHeaderBlockFragment());
        FlowControlStrategy flowControl = getFlowControlStrategyFactory().newFlowControlStrategy();
        HTTP2ServerSession session = new HTTP2ServerSession(connector.getScheduler(), endPoint, generator, listener, flowControl);
        session.setMaxLocalStreams(getMaxConcurrentStreams());
        session.setMaxRemoteStreams(getMaxConcurrentStreams());
        // For a single stream in a connection, there will be a race between
        // the stream idle timeout and the connection idle timeout. However,
        // the typical case is that the connection will be busier and the
        // stream idle timeout will expire earlier than the connection's.
        long streamIdleTimeout = getStreamIdleTimeout();
        if (streamIdleTimeout > 0)
            session.setStreamIdleTimeout(streamIdleTimeout);
        session.setInitialSessionRecvWindow(getInitialSessionRecvWindow());
        session.setWriteThreshold(getHttpConfiguration().getOutputBufferSize());
        session.setConnectProtocolEnabled(isConnectProtocolEnabled());

        ServerParser parser = newServerParser(connector, session, getRateControlFactory().newRateControl(endPoint));
        parser.setMaxFrameLength(getMaxFrameLength());
        parser.setMaxSettingsKeys(getMaxSettingsKeys());

        RetainableByteBufferPool retainableByteBufferPool = connector.getByteBufferPool().asRetainableByteBufferPool();

        HTTP2Connection connection = new HTTP2ServerConnection(retainableByteBufferPool, connector,
            endPoint, httpConfiguration, parser, session, getInputBufferSize(), listener);
        connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
        connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
        connection.addEventListener(sessionContainer);
        return configure(connection, connector, endPoint);
    }

    protected abstract ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint);

    private ServerParser newServerParser(Connector connector, ServerParser.Listener listener, RateControl rateControl)
    {
        return new ServerParser(connector.getByteBufferPool(), listener, getMaxDynamicTableSize(), getHttpConfiguration().getRequestHeaderSize(), rateControl);
    }

    @ManagedObject("The container of HTTP/2 sessions")
    public static class HTTP2SessionContainer implements Connection.Listener, Graceful, Dumpable
    {
        private final Set<ISession> sessions = ConcurrentHashMap.newKeySet();
        private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();

        @Override
        public void onOpened(Connection connection)
        {
            ISession session = ((HTTP2Connection)connection).getSession();
            sessions.add(session);
            LifeCycle.start(session);
            if (isShutdown())
                shutdown(session);
        }

        @Override
        public void onClosed(Connection connection)
        {
            ISession session = ((HTTP2Connection)connection).getSession();
            if (sessions.remove(session))
                LifeCycle.stop(session);
        }

        public Set<Session> getSessions()
        {
            return new HashSet<>(sessions);
        }

        @ManagedAttribute(value = "The number of HTTP/2 sessions", readonly = true)
        public int getSize()
        {
            return sessions.size();
        }

        @Override
        public CompletableFuture<Void> shutdown()
        {
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (shutdown.compareAndSet(null, result))
            {
                CompletableFuture.allOf(sessions.stream().map(this::shutdown).toArray(CompletableFuture[]::new))
                    .whenComplete((v, x) ->
                    {
                        if (x == null)
                            result.complete(v);
                        else
                            result.completeExceptionally(x);
                    });
                return result;
            }
            else
            {
                return shutdown.get();
            }
        }

        @Override
        public boolean isShutdown()
        {
            return shutdown.get() != null;
        }

        private CompletableFuture<Void> shutdown(ISession session)
        {
            return session.shutdown();
        }

        @Override
        public String dump()
        {
            return Dumpable.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, this, sessions);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[size=%d]", getClass().getSimpleName(), hashCode(), getSize());
        }
    }
}
