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

package org.eclipse.jetty.http2.client;

import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>HTTP2Client provides an asynchronous, non-blocking implementation
 * to send HTTP/2 frames to a server.</p>
 * <p>Typical usage:</p>
 * <pre>
 * // Create and start HTTP2Client.
 * HTTP2Client client = new HTTP2Client();
 * client.start();
 * SslContextFactory sslContextFactory = client.getClientConnector().getSslContextFactory();
 *
 * // Connect to host.
 * String host = "webtide.com";
 * int port = 443;
 *
 * FuturePromise&lt;Session&gt; sessionPromise = new FuturePromise&lt;&gt;();
 * client.connect(sslContextFactory, new InetSocketAddress(host, port), new ServerSessionListener() {}, sessionPromise);
 *
 * // Obtain the client Session object.
 * Session session = sessionPromise.get(5, TimeUnit.SECONDS);
 *
 * // Prepare the HTTP request headers.
 * HttpFields requestFields = HttpFields.build();
 * requestFields.put("User-Agent", client.getClass().getName() + "/" + Jetty.VERSION);
 * // Prepare the HTTP request object.
 * MetaData.Request request = new MetaData.Request("PUT", HttpURI.from("https://" + host + ":" + port + "/"), HttpVersion.HTTP_2, requestFields);
 * // Create the HTTP/2 HEADERS frame representing the HTTP request.
 * HeadersFrame headersFrame = new HeadersFrame(request, null, false);
 *
 * // Prepare the listener to receive the HTTP response frames.
 * Stream.Listener responseListener = new new Stream.Listener()
 * {
 *      &#64;Override
 *      public void onHeaders(Stream stream, HeadersFrame frame)
 *      {
 *          System.err.println(frame);
 *      }
 *
 *      &#64;Override
 *      public void onData(Stream stream, DataFrame frame, Callback callback)
 *      {
 *          System.err.println(frame);
 *          callback.succeeded();
 *      }
 * };
 *
 * // Send the HEADERS frame to create a stream.
 * FuturePromise&lt;Stream&gt; streamPromise = new FuturePromise&lt;&gt;();
 * session.newStream(headersFrame, streamPromise, responseListener);
 * Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
 *
 * // Use the Stream object to send request content, if any, using a DATA frame.
 * ByteBuffer content = ...;
 * DataFrame requestContent = new DataFrame(stream.getId(), content, true);
 * stream.data(requestContent, Callback.NOOP);
 *
 * // When done, stop the client.
 * client.stop();
 * </pre>
 */
@ManagedObject
public class HTTP2Client extends ContainerLifeCycle
{
    private final ClientConnector connector;
    private int inputBufferSize = 8192;
    private List<String> protocols = List.of("h2");
    private int initialSessionRecvWindow = 16 * 1024 * 1024;
    private int initialStreamRecvWindow = 8 * 1024 * 1024;
    private int maxFrameLength = Frame.DEFAULT_MAX_LENGTH;
    private int maxConcurrentPushedStreams = 32;
    private int maxSettingsKeys = SettingsFrame.DEFAULT_MAX_KEYS;
    private int maxDynamicTableSize = 4096;
    private int maxHeaderBlockFragment = 0;
    private FlowControlStrategy.Factory flowControlStrategyFactory = () -> new BufferingFlowControlStrategy(0.5F);
    private long streamIdleTimeout;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private boolean isUseALPN = true;

    public HTTP2Client()
    {
        this(new ClientConnector());
    }

    public HTTP2Client(ClientConnector connector)
    {
        this.connector = connector;
        addBean(connector);
    }

    public ClientConnector getClientConnector()
    {
        return connector;
    }

    public Executor getExecutor()
    {
        return connector.getExecutor();
    }

    public void setExecutor(Executor executor)
    {
        connector.setExecutor(executor);
    }

    public Scheduler getScheduler()
    {
        return connector.getScheduler();
    }

    public void setScheduler(Scheduler scheduler)
    {
        connector.setScheduler(scheduler);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return connector.getByteBufferPool();
    }

    public void setByteBufferPool(ByteBufferPool bufferPool)
    {
        connector.setByteBufferPool(bufferPool);
    }

    public FlowControlStrategy.Factory getFlowControlStrategyFactory()
    {
        return flowControlStrategyFactory;
    }

    public void setFlowControlStrategyFactory(FlowControlStrategy.Factory flowControlStrategyFactory)
    {
        this.flowControlStrategyFactory = flowControlStrategyFactory;
    }

    @ManagedAttribute("The number of selectors")
    public int getSelectors()
    {
        return connector.getSelectors();
    }

    public void setSelectors(int selectors)
    {
        connector.setSelectors(selectors);
    }

    @ManagedAttribute("The idle timeout in milliseconds")
    public long getIdleTimeout()
    {
        return connector.getIdleTimeout().toMillis();
    }

    public void setIdleTimeout(long idleTimeout)
    {
        connector.setIdleTimeout(Duration.ofMillis(idleTimeout));
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

    @ManagedAttribute("The connect timeout in milliseconds")
    public long getConnectTimeout()
    {
        return connector.getConnectTimeout().toMillis();
    }

    public void setConnectTimeout(long connectTimeout)
    {
        connector.setConnectTimeout(Duration.ofMillis(connectTimeout));
    }

    @ManagedAttribute("Whether the connect() operation is blocking")
    public boolean isConnectBlocking()
    {
        return connector.isConnectBlocking();
    }

    public void setConnectBlocking(boolean connectBlocking)
    {
        connector.setConnectBlocking(connectBlocking);
    }

    public SocketAddress getBindAddress()
    {
        return connector.getBindAddress();
    }

    public void setBindAddress(SocketAddress bindAddress)
    {
        connector.setBindAddress(bindAddress);
    }

    @ManagedAttribute("The size of the buffer used to read from the network")
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    @ManagedAttribute("The ALPN protocol list")
    public List<String> getProtocols()
    {
        return protocols;
    }

    public void setProtocols(List<String> protocols)
    {
        this.protocols = protocols;
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

    @ManagedAttribute("The max frame length in bytes")
    public int getMaxFrameLength()
    {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength)
    {
        this.maxFrameLength = maxFrameLength;
    }

    @ManagedAttribute("The max number of concurrent pushed streams")
    public int getMaxConcurrentPushedStreams()
    {
        return maxConcurrentPushedStreams;
    }

    public void setMaxConcurrentPushedStreams(int maxConcurrentPushedStreams)
    {
        this.maxConcurrentPushedStreams = maxConcurrentPushedStreams;
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

    @ManagedAttribute("The HPACK dynamic table maximum size")
    public int getMaxDynamicTableSize()
    {
        return maxDynamicTableSize;
    }

    public void setMaxDynamicTableSize(int maxDynamicTableSize)
    {
        this.maxDynamicTableSize = maxDynamicTableSize;
    }

    @ManagedAttribute("The max size of header block fragments")
    public int getMaxHeaderBlockFragment()
    {
        return maxHeaderBlockFragment;
    }

    public void setMaxHeaderBlockFragment(int maxHeaderBlockFragment)
    {
        this.maxHeaderBlockFragment = maxHeaderBlockFragment;
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

    @ManagedAttribute(value = "Whether ALPN should be used when establishing connections")
    public boolean isUseALPN()
    {
        return isUseALPN;
    }

    public void setUseALPN(boolean useALPN)
    {
        isUseALPN = useALPN;
    }

    public CompletableFuture<Session> connect(SocketAddress address, Session.Listener listener)
    {
        return connect(null, address, listener);
    }

    public void connect(SocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        // Prior-knowledge clear-text HTTP/2 (h2c).
        connect(null, address, listener, promise);
    }

    public CompletableFuture<Session> connect(SslContextFactory sslContextFactory, SocketAddress address, Session.Listener listener)
    {
        return Promise.Completable.with(p -> connect(sslContextFactory, address, listener, p));
    }

    public void connect(SslContextFactory sslContextFactory, SocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        connect(sslContextFactory, address, listener, promise, null);
    }

    public void connect(SslContextFactory sslContextFactory, SocketAddress address, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        ClientConnectionFactory factory = newClientConnectionFactory(sslContextFactory);
        connect(address, factory, listener, promise, context);
    }

    public void connect(SocketAddress address, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        context = contextFrom(factory, listener, promise, context);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.connect(address, context);
    }

    public void accept(SslContextFactory sslContextFactory, SocketChannel channel, Session.Listener listener, Promise<Session> promise)
    {
        ClientConnectionFactory factory = newClientConnectionFactory(sslContextFactory);
        accept(channel, factory, listener, promise);
    }

    public void accept(SocketChannel channel, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise)
    {
        Map<String, Object> context = contextFrom(factory, listener, promise, null);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.accept(channel, context);
    }

    private Map<String, Object> contextFrom(ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        if (context == null)
            context = new ConcurrentHashMap<>();
        context.put(HTTP2ClientConnectionFactory.CLIENT_CONTEXT_KEY, this);
        context.put(HTTP2ClientConnectionFactory.SESSION_LISTENER_CONTEXT_KEY, listener);
        context.put(HTTP2ClientConnectionFactory.SESSION_PROMISE_CONTEXT_KEY, promise);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, factory);
        return context;
    }

    private ClientConnectionFactory newClientConnectionFactory(SslContextFactory sslContextFactory)
    {
        ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();
        if (sslContextFactory != null)
        {
            if (isUseALPN())
                factory = new ALPNClientConnectionFactory(getExecutor(), factory, getProtocols());
            factory = new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), factory);
        }
        return factory;
    }
}
