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

package org.eclipse.jetty.quic.client;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicClient extends ContainerLifeCycle implements AutoCloseable
{
    public static final String CONTEXT_KEY = QuicClient.class.getName();
    public static final String SESSION_PROMISE_CONTEXT_KEY = Session.class.getName() + ".promise";
    public static final String SESSION_LISTENER_CONTEXT_KEY = Session.Listener.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(QuicClient.class);

    private final QuicClientQuicConfiguration quicConfiguration;
    private final ClientConnector clientConnector;
    private List<String> protocols = List.of("http/1.1", "hq-interop");

    public QuicClient(QuicClientQuicConfiguration quicConfiguration)
    {
        this(quicConfiguration, new ClientConnector());
    }

    public QuicClient(QuicClientQuicConfiguration quicConfiguration, ClientConnector clientConnector)
    {
        this.quicConfiguration = Objects.requireNonNull(quicConfiguration);
        installBean(quicConfiguration);
        this.clientConnector = Objects.requireNonNull(clientConnector);
        installBean(clientConnector);
    }

    public ClientConnector getClientConnector()
    {
        return clientConnector;
    }

    public List<String> getApplicationProtocols()
    {
        return protocols;
    }

    public void setApplicationProtocols(List<String> protocols)
    {
        this.protocols = List.copyOf(protocols);
    }

    @Override
    public void close() throws Exception
    {
        stop();
    }

    public void connect(SocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        connect(new QuicTransport(quicConfiguration), clientConnector.getSslContextFactory(), address, listener, null, promise);
    }

    public void connect(Transport transport, SslContextFactory.Client sslContextFactory, SocketAddress address, Session.Listener listener, Map<String, Object> context, Promise<Session> promise)
    {
        if (context == null)
            context = new ConcurrentHashMap<>();
        context.put(QuicClient.CONTEXT_KEY, this);
        context.put(QuicClient.SESSION_LISTENER_CONTEXT_KEY, listener);
        context.put(QuicClient.SESSION_PROMISE_CONTEXT_KEY, promise);
        context.put(ClientConnector.CONTEXT_KEY, getClientConnector());
        context.put(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY, getApplicationProtocols());
        context.computeIfAbsent(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY, _ -> sslContextFactory);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(_ ->
        {
        }, promise::failed));
        context.put(ClientConnectionFactory.CONTEXT_KEY, resolveClientConnectionFactory(transport));
        context.put(Transport.CONTEXT_KEY, transport);

        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {}", address);

        transport.connect(address, context);
    }

    private ClientConnectionFactory resolveClientConnectionFactory(Transport transport)
    {
        return transport.newClientConnectionFactory(clientConnector, ((endPoint, context) -> null));
    }
}
