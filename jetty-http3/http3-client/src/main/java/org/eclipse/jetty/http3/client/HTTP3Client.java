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

package org.eclipse.jetty.http3.client;

import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.client.QuicClientConnectorConfigurator;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSessionContainer;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <pre>
 *             / dgramEP1 - ClientQuiConnection -* ClientQuicSession - ClientProtocolSession
 * HTTP3Client                                                                             / ControlStream
 *             \ dgramEP3 - ClientQuiConnection -* ClientQuicSession - ClientHTTP3Session -* HTTP3Streams
 * </pre>
 */
public class HTTP3Client extends ContainerLifeCycle
{
    public static final String CLIENT_CONTEXT_KEY = HTTP3Client.class.getName();
    public static final String SESSION_LISTENER_CONTEXT_KEY = CLIENT_CONTEXT_KEY + ".listener";
    public static final String SESSION_PROMISE_CONTEXT_KEY = CLIENT_CONTEXT_KEY + ".promise";
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Client.class);

    private final HTTP3Configuration http3Configuration = new HTTP3Configuration();
    private final QuicSessionContainer container = new QuicSessionContainer();
    private final ClientConnector connector;
    private final QuicConfiguration quicConfiguration;

    public HTTP3Client()
    {
        QuicClientConnectorConfigurator configurator = new QuicClientConnectorConfigurator(this::configureConnection);
        this.connector = new ClientConnector(configurator);
        this.quicConfiguration = configurator.getQuicConfiguration();
        addBean(connector);
        addBean(quicConfiguration);
        addBean(http3Configuration);
        addBean(container);
        // Allow the mandatory unidirectional streams, plus pushed streams.
        quicConfiguration.setMaxUnidirectionalRemoteStreams(48);
        quicConfiguration.setUnidirectionalStreamRecvWindow(4 * 1024 * 1024);
        quicConfiguration.setProtocols(List.of("h3"));
    }

    public ClientConnector getClientConnector()
    {
        return connector;
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return quicConfiguration;
    }

    public HTTP3Configuration getHTTP3Configuration()
    {
        return http3Configuration;
    }

    public CompletableFuture<Session.Client> connect(SocketAddress address, Session.Client.Listener listener)
    {
        Map<String, Object> context = new ConcurrentHashMap<>();
        return connect(address, listener, context);
    }

    public CompletableFuture<Session.Client> connect(SocketAddress address, Session.Client.Listener listener, Map<String, Object> context)
    {
        Promise.Completable<Session.Client> completable = new Promise.Completable<>();
        context.put(CLIENT_CONTEXT_KEY, this);
        context.put(SESSION_LISTENER_CONTEXT_KEY, listener);
        context.put(SESSION_PROMISE_CONTEXT_KEY, completable);
        context.computeIfAbsent(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, key -> new HTTP3ClientConnectionFactory());
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, completable::failed));

        if (LOG.isDebugEnabled())
            LOG.debug("connecting to {}", address);

        connector.connect(address, context);
        return completable;
    }

    private Connection configureConnection(Connection connection)
    {
        if (connection instanceof QuicConnection)
        {
            QuicConnection quicConnection = (QuicConnection)connection;
            quicConnection.addEventListener(container);
            quicConnection.setInputBufferSize(getHTTP3Configuration().getInputBufferSize());
            quicConnection.setOutputBufferSize(getHTTP3Configuration().getOutputBufferSize());
            quicConnection.setUseInputDirectByteBuffers(getHTTP3Configuration().isUseInputDirectByteBuffers());
            quicConnection.setUseOutputDirectByteBuffers(getHTTP3Configuration().isUseOutputDirectByteBuffers());
        }
        return connection;
    }

    public CompletableFuture<Void> shutdown()
    {
        return container.shutdown();
    }
}
