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

package org.eclipse.jetty.http3.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.http.internal.HttpConnectionOverHTTP3;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;

public class HttpClientTransportOverHTTP3 extends AbstractHttpClientTransport
{
    private final HTTP3Client client;

    public HttpClientTransportOverHTTP3(HTTP3Client client)
    {
        this.client = Objects.requireNonNull(client);
        addBean(client);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            return new MultiplexConnectionPool(destination, httpClient.getMaxConnectionsPerDestination(), destination, httpClient.getMaxRequestsQueuedPerDestination());
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
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of("h3"), false));
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        SocketAddress address = origin.getAddress().getSocketAddress();
        return new MultiplexHttpDestination(getHttpClient(), origin, getHTTP3Client().getClientConnector().isIntrinsicallySecure(address));
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

        getHTTP3Client().connect(address, new SessionClientListener(context), context);
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        return null;
    }

    private class SessionClientListener implements Session.Client.Listener
    {
        private final Map<String, Object> context;

        private SessionClientListener(Map<String, Object> context)
        {
            this.context = context;
        }

        @SuppressWarnings("unchecked")
        private Promise<org.eclipse.jetty.client.api.Connection> httpConnectionPromise()
        {
            return (Promise<org.eclipse.jetty.client.api.Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        }

        @Override
        public void onSettings(Session session, SettingsFrame frame)
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            HttpConnectionOverHTTP3 connection = new HttpConnectionOverHTTP3(destination, (HTTP3Session)session);
            httpConnectionPromise().succeeded(connection);
        }
    }
}
