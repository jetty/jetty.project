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

package org.eclipse.jetty.http3.client;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.internal.ClientHTTP3Session;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.common.SessionContainer;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// `HTTP3Client` provides an asynchronous, non-blocking implementation to send
/// HTTP/3 frames to a server.
///
/// Typical usage:
///
/// ```java
/// // Client-side QUIC configuration to configure QUIC properties.
/// QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
/// // Create the HTTP3Client instance.
/// HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
/// // To configure HTTP/3 properties.
/// HTTP3Configuration h3Config = http3Client.getHTTP3Configuration();
/// http3Client.start();
/// // HTTP3Client request/response usage.
/// // Connect to host.
/// String host = "jetty.org";
/// int port = 443;
/// Session.Client session = Blocker.blockWithPromise(p -> http3Client
///     .connect(new QuicheTransport(clientQuicConfig), new InetSocketAddress(host, port), new Session.Client.Listener() {}, p));
/// // Prepare the HTTP request headers.
/// HttpFields.Mutable requestFields = HttpFields.build();
/// requestFields.put("User-Agent", http3Client.getClass().getName() + "/" + Jetty.VERSION);
/// // Prepare the HTTP request object.
/// MetaData.Request request = new MetaData.Request("PUT", HttpURI.from("https://" + host + ":" + port + "/"), HttpVersion.HTTP_3, requestFields);
/// // Create the HTTP/3 HEADERS frame representing the HTTP request.
/// HeadersFrame headersFrame = new HeadersFrame(request, false);
/// // Send the HEADERS frame to create a request stream.
/// Stream.Client stream = Blocker.blockWithPromise(p -> session.newRequest(headersFrame, new Stream.Client.Listener()
/// {
///     @Override
///     public void onResponse(Stream.Client stream, HeadersFrame frame)
///     {
///         // Inspect the response status and headers.
///         MetaData.Response response = (MetaData.Response)frame.getMetaData();
///         // Demand for response content.
///         stream.demand();
///     }
///
///     @Override
///     public void onDataAvailable(Stream.Client stream)
///     {
///         Content.Chunk chunk = stream.read();
///         if (chunk == null)
///         {
///             stream.demand();
///             return;
///         }
///         // Process the response content chunk.
///         process(chunk);
///         // Release the chunk.
///         chunk.release();
///         // Demand for more response content.
///         if (!chunk.isLast())
///             stream.demand();
///     }
/// }, p));
/// // Use the Stream.Client object to send request content, if any, using a DATA frame.
/// ByteBuffer requestChunk1 = UTF_8.encode("hello");
/// stream.data(new DataFrame(requestChunk1, false), new Promise.Invocable.NonBlocking()
/// {
///     @Override
///     public void succeeded(Stream s)
///     {
///         // Subsequent sends must wait for previous sends to complete.
///         ByteBuffer requestChunk2 = UTF_8.encode("world");
///         return s.data(new DataFrame(requestChunk2, true), Promise.Invocable.noop());
///     }
/// });
/// ```
///
/// IMPLEMENTATION NOTES.
///
/// Each call to [#connect(Transport, SocketAddress, Session.Client.Listener, Promise.Invocable)]
/// creates a new [DatagramChannelEndPoint] with the correspondent QUIC [Connection].
///
/// Each QUIC connection manages one QUIC [Session]
/// with the corresponding [ClientHTTP3Session].
///
/// Each [ClientHTTP3Session] manages the mandatory QPACK encoder, QPACK decoder
/// and control unidirectional streams, plus zero or more request/response bidirectional
/// streams.
///
/// HTTP/3+QUIC support is experimental and not suited for production use.
/// APIs may change incompatibly between releases.
public class HTTP3Client extends ContainerLifeCycle implements AutoCloseable
{
    public static final String CONTEXT_KEY = HTTP3Client.class.getName();
    public static final String SESSION_PROMISE_CONTEXT_KEY = Session.Client.class.getName() + ".promise";
    public static final String SESSION_LISTENER_CONTEXT_KEY = Session.Client.Listener.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Client.class);

    private final SessionContainer container = new SessionContainer();
    private final HTTP3Configuration http3Configuration = new HTTP3Configuration();
    private final ClientQuicConfiguration quicConfiguration;
    private final ClientConnector connector;
    private List<String> protocols = List.of("h3");
    private boolean useALPN = true;

    public HTTP3Client(ClientQuicConfiguration quicConfiguration)
    {
        this(quicConfiguration, new ClientConnector());
    }

    public HTTP3Client(ClientQuicConfiguration quicConfiguration, ClientConnector connector)
    {
        this.quicConfiguration = quicConfiguration;
        this.connector = connector;
        installBean(connector);
    }

    public HTTP3Configuration getHTTP3Configuration()
    {
        return http3Configuration;
    }

    public ClientQuicConfiguration getClientQuicConfiguration()
    {
        return quicConfiguration;
    }

    public ClientConnector getClientConnector()
    {
        return connector;
    }

    public List<String> getApplicationProtocols()
    {
        return protocols;
    }

    public void setApplicationProtocols(List<String> protocols)
    {
        this.protocols = List.copyOf(protocols);
    }

    public boolean isUseALPN()
    {
        return useALPN;
    }

    public void setUseALPN(boolean useALPN)
    {
        this.useALPN = useALPN;
    }

    @Override
    protected void doStart() throws Exception
    {
        LOG.info("HTTP/3+QUIC support is experimental and not suited for production use.");
        addBean(quicConfiguration);
        addBean(container);
        addBean(http3Configuration);
        quicConfiguration.addEventListener(container);
        super.doStart();
    }

    /**
     * <p>Connect for HTTP/3, clear-text or intrinsically secure depending on the {@link Transport}.</p>
     *
     * @param transport the {@code Transport} to use
     * @param socketAddress the address to connect to
     * @param listener the listener to notify of session events
     * @param promise a {@code Promise.Invocable} that is completed when the connect operation is complete
     */
    public void connect(Transport transport, SocketAddress socketAddress, Session.Client.Listener listener, Promise.Invocable<Session.Client> promise)
    {
        connect(transport, getClientConnector().getSslContextFactory(), socketAddress, listener, promise);
    }

    /**
     * <p>Connect for HTTP/3, clear-text, or secure, or intrinsically secure depending on the {@link Transport}.</p>
     *
     * @param transport the {@code Transport} to use
     * @param sslContextFactory {@code null} for clear-text, non-{@code null} for secure HTTP/3
     * @param socketAddress the address to connect to
     * @param listener the listener to notify of session events
     * @param promise a {@code Promise.Invocable} that is completed when the connect operation is complete
     */
    public void connect(Transport transport, SslContextFactory.Client sslContextFactory, SocketAddress socketAddress, Session.Client.Listener listener, Promise.Invocable<Session.Client> promise)
    {
        connect(transport, sslContextFactory, socketAddress, listener, null, promise);
    }

    public void connect(Transport transport, SslContextFactory.Client sslContextFactory, SocketAddress socketAddress, Session.Client.Listener listener, Map<String, Object> context, Promise.Invocable<Session.Client> promise)
    {
        if (context == null)
            context = new ConcurrentHashMap<>();
        context.put(HTTP3Client.CONTEXT_KEY, this);
        context.put(HTTP3Client.SESSION_LISTENER_CONTEXT_KEY, listener);
        context.put(HTTP3Client.SESSION_PROMISE_CONTEXT_KEY, promise);
        context.put(ClientConnector.CONTEXT_KEY, getClientConnector());
        context.put(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY, getApplicationProtocols());
        context.computeIfAbsent(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY, key -> sslContextFactory);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        context.put(ClientConnectionFactory.CONTEXT_KEY, resolveClientConnectionFactory(transport, sslContextFactory, context));
        context.put(Transport.CONTEXT_KEY, transport);

        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {}", socketAddress);

        transport.connect(socketAddress, context);
    }

    public CompletableFuture<Void> shutdown()
    {
        return container.shutdown().whenComplete((r, x) -> LifeCycle.stop(this));
    }

    @Override
    public void close() throws Exception
    {
        stop();
    }

    private ClientConnectionFactory resolveClientConnectionFactory(Transport transport, SslContextFactory.Client sslContextFactory, Map<String, Object> context)
    {
        ClientConnectionFactory factory = (ClientConnectionFactory)context.get(ClientConnectionFactory.CONTEXT_KEY);
        if (factory == null)
            factory = new HTTP3ClientConnectionFactory();
        ClientConnector clientConnector = getClientConnector();
        factory = transport.newClientConnectionFactory(clientConnector, factory);
        if (sslContextFactory != null && !transport.isIntrinsicallySecure())
        {
            @SuppressWarnings("unchecked")
            List<String> applicationProtocols = (List<String>)context.get(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY);
            if (isUseALPN() && !applicationProtocols.isEmpty())
                factory = new ALPNClientConnectionFactory(clientConnector.getExecutor(), factory, applicationProtocols);
            factory = clientConnector.newSslClientConnectionFactory(sslContextFactory, factory);
        }
        ClientConnectionFactory.Decorator decorator = (ClientConnectionFactory.Decorator)context.get(ClientConnectionFactory.Decorator.CONTEXT_KEY);
        if (decorator != null)
            factory = decorator.apply(factory);
        return factory;
    }
}
