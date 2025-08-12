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

package org.eclipse.jetty.http3.client.transport;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http3.HTTP3Configuration;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientConnectionFactory;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.HttpConnectionOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.SessionClientListener;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpClientTransportOverHTTP3 extends AbstractHttpClientTransport implements ProtocolSession.Factory
{
    private final HTTP3ClientConnectionFactory factory = new HTTP3ClientConnectionFactory();
    private final HTTP3Client http3Client;
    private final Transport transport;

    public HttpClientTransportOverHTTP3(HTTP3Client http3Client, Transport transport)
    {
        this.http3Client = Objects.requireNonNull(http3Client);
        installBean(http3Client);
        this.transport = Objects.requireNonNull(transport);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            return new MultiplexConnectionPool(destination, httpClient.getMaxConnectionsPerDestination(), 1);
        });
    }

    public HTTP3Client getHTTP3Client()
    {
        return http3Client;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!http3Client.isStarted())
            configure(getHttpClient(), http3Client);
        super.doStart();
    }

    static void configure(HttpClient httpClient, HTTP3Client http3Client)
    {
        ClientConnector clientConnector = http3Client.getClientConnector();
        clientConnector.setExecutor(httpClient.getExecutor());
        clientConnector.setScheduler(httpClient.getScheduler());
        clientConnector.setByteBufferPool(httpClient.getByteBufferPool());
        clientConnector.setConnectTimeout(Duration.ofMillis(httpClient.getConnectTimeout()));
        clientConnector.setConnectBlocking(httpClient.isConnectBlocking());
        clientConnector.setBindAddress(httpClient.getBindAddress());
        clientConnector.setIdleTimeout(Duration.ofMillis(httpClient.getIdleTimeout()));
        HTTP3Configuration configuration = http3Client.getHTTP3Configuration();
        configuration.setInputBufferSize(httpClient.getResponseBufferSize());
        configuration.setUseInputDirectByteBuffers(httpClient.isUseInputDirectByteBuffers());
        configuration.setUseOutputDirectByteBuffers(httpClient.isUseOutputDirectByteBuffers());
        configuration.setMaxRequestHeadersSize(httpClient.getMaxRequestHeadersSize());
        configuration.setMaxResponseHeadersSize(httpClient.getMaxResponseHeadersSize());
    }

    @Override
    public Origin newOrigin(Request request)
    {
        HttpVersion version = request.getVersion();
        HttpVersion http3 = HttpVersion.HTTP_3;
        if (((HttpRequest)request).isVersionExplicit() && version != http3)
            throw new HttpRequestException("Cannot send explicit %s requests with %s transport".formatted(version, http3), request);
        Transport provided = request.getTransport();
        if (provided == null)
            request.transport(transport);
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of("h3"), false));
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        return new HttpDestination(getHttpClient(), origin);
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        Transport transport = (Transport)context.get(Transport.CONTEXT_KEY);
        SslContextFactory.Client sslContextFactory = (SslContextFactory.Client)context.get(ClientConnector.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
        SessionClientListener listener = new TransportSessionClientListener(context);
        getHTTP3Client().connect(transport, sslContextFactory, address, listener, context, new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void succeeded(org.eclipse.jetty.http3.api.Session.Client result)
            {
                listener.onConnect(result, null);
            }

            @Override
            public void failed(Throwable x)
            {
                listener.onConnect(null, x);
            }
        });
    }

    @Override
    public ProtocolSession newProtocolSession(Session session, Map<String, Object> context)
    {
        return factory.newProtocolSession(session, context);
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
