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

package org.eclipse.jetty.http3.client.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientConnectionFactory;
import org.eclipse.jetty.http3.client.internal.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.HttpConnectionOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.SessionClientListener;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicSession;

public class HttpClientTransportOverHTTP3 extends AbstractHttpClientTransport implements ProtocolSession.Factory
{
    private final HTTP3ClientConnectionFactory factory = new HTTP3ClientConnectionFactory();
    private final HTTP3Client client;

    public HttpClientTransportOverHTTP3(HTTP3Client client)
    {
        this.client = Objects.requireNonNull(client);
        addBean(client);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            return new MultiplexConnectionPool(destination, httpClient.getMaxConnectionsPerDestination(), 1);
        });
    }

    public HTTP3Client getHTTP3Client()
    {
        return client;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!client.isStarted())
        {
            HttpClient httpClient = getHttpClient();
            ClientConnector clientConnector = this.client.getClientConnector();
            clientConnector.setExecutor(httpClient.getExecutor());
            clientConnector.setScheduler(httpClient.getScheduler());
            clientConnector.setByteBufferPool(httpClient.getByteBufferPool());
            clientConnector.setConnectTimeout(Duration.ofMillis(httpClient.getConnectTimeout()));
            clientConnector.setConnectBlocking(httpClient.isConnectBlocking());
            clientConnector.setBindAddress(httpClient.getBindAddress());
            clientConnector.setIdleTimeout(Duration.ofMillis(httpClient.getIdleTimeout()));
            HTTP3Configuration configuration = client.getHTTP3Configuration();
            configuration.setInputBufferSize(httpClient.getResponseBufferSize());
            configuration.setUseInputDirectByteBuffers(httpClient.isUseInputDirectByteBuffers());
            configuration.setUseOutputDirectByteBuffers(httpClient.isUseOutputDirectByteBuffers());
        }
        super.doStart();
    }

    @Override
    public Origin newOrigin(Request request)
    {
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of("h3"), false));
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        SocketAddress address = origin.getAddress().getSocketAddress();
        return new HttpDestination(getHttpClient(), origin, getHTTP3Client().getClientConnector().isIntrinsicallySecure(address));
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        connect((SocketAddress)address, context);
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        HttpClient httpClient = getHttpClient();
        ClientConnector clientConnector = client.getClientConnector();
        clientConnector.setConnectTimeout(Duration.ofMillis(httpClient.getConnectTimeout()));
        clientConnector.setConnectBlocking(httpClient.isConnectBlocking());
        clientConnector.setBindAddress(httpClient.getBindAddress());

        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());

        SessionClientListener listener = new TransportSessionClientListener(context);
        getHTTP3Client().connect(address, listener, context)
            .whenComplete(listener::onConnect);
    }

    @Override
    public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
    {
        return factory.newProtocolSession(quicSession, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        return factory.newConnection(endPoint, context);
    }

    protected Connection newConnection(Destination destination, HTTP3SessionClient session)
    {
        return new HttpConnectionOverHTTP3(destination, session);
    }

    private class TransportSessionClientListener extends SessionClientListener
    {
        private TransportSessionClientListener(Map<String, Object> context)
        {
            super(context);
        }

        @Override
        protected Connection newConnection(Destination destination, HTTP3SessionClient session)
        {
            return HttpClientTransportOverHTTP3.this.newConnection(destination, session);
        }
    }
}
