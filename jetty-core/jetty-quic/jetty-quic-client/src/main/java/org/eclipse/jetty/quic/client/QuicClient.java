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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Version;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicClient extends ContainerLifeCycle
{
    public static final String CONTEXT_KEY = QuicClient.class.getName();
    public static final String SESSION_PROMISE_CONTEXT_KEY = Session.class.getName() + ".promise";
    public static final String SESSION_LISTENER_CONTEXT_KEY = Session.Listener.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(QuicClient.class);

    private final QuicClientQuicConfiguration quicConfiguration;
    private final ClientConnector clientConnector;

    public QuicClient(QuicClientQuicConfiguration quicConfiguration, ClientConnector clientConnector)
    {
        this.quicConfiguration = quicConfiguration;
        this.clientConnector = clientConnector;
    }

    public ClientConnector getClientConnector()
    {
        return clientConnector;
    }

    public void connect(SocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        connect(Version.V1, address, listener, promise);
    }

    public void connect(Version version, SocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        QuicTransport transport = new QuicTransport(null);

        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put(QuicClient.CONTEXT_KEY, this);
        context.put(QuicClient.SESSION_LISTENER_CONTEXT_KEY, listener);
        context.put(QuicClient.SESSION_PROMISE_CONTEXT_KEY, promise);
        context.put(ClientConnector.CONTEXT_KEY, getClientConnector());
//        context.put(ClientConnector.APPLICATION_PROTOCOLS_CONTEXT_KEY, getApplicationProtocols());
//        context.computeIfAbsent(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY, key -> sslContextFactory);
//        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
//        context.put(ClientConnectionFactory.CONTEXT_KEY, resolveClientConnectionFactory(transport, sslContextFactory, context));
        context.put(Transport.CONTEXT_KEY, transport);

        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {}", address);

        transport.connect(address, context);
    }
}
