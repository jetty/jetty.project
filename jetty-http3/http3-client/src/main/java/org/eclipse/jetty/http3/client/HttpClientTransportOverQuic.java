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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("The QUIC client transport")
public class HttpClientTransportOverQuic extends AbstractHttpClientTransport
{
    private final ClientConnectionFactory connectionFactory = new HttpClientConnectionFactory();
    private final ClientQuicConnector connector;
    private final Origin.Protocol protocol;
    private final QuicheConfig quicheConfig;
    private int initialSessionRecvWindow = 16 * 1024 * 1024;
    private int initialStreamRecvWindow = 8 * 1024 * 1024;
    private int maxConcurrentPushedStreams = 32;

    public HttpClientTransportOverQuic()
    {
        this(HttpClientConnectionFactory.HTTP11);
    }

    public HttpClientTransportOverQuic(ClientConnectionFactory.Info... factoryInfos)
    {
        List<String> protocolNames = Arrays.stream(factoryInfos).flatMap(info -> info.getProtocols(true).stream()).collect(Collectors.toList());
        quicheConfig = new QuicheConfig();
        quicheConfig.setApplicationProtos(protocolNames.toArray(new String[0]));

        protocol = new Origin.Protocol(protocolNames, false);
        connector = new ClientQuicConnector(quicheConfig);
        addBean(connector);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            int maxConnections = httpClient.getMaxConnectionsPerDestination();
            return new MultiplexConnectionPool(destination, maxConnections, destination, 1);
        });
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

    @ManagedAttribute("The max number of concurrent pushed streams")
    public int getMaxConcurrentPushedStreams()
    {
        return maxConcurrentPushedStreams;
    }

    public void setMaxConcurrentPushedStreams(int maxConcurrentPushedStreams)
    {
        this.maxConcurrentPushedStreams = maxConcurrentPushedStreams;
    }

    @Override
    protected void doStart() throws Exception
    {
        // TODO make these QuicheConfig settings configurable
        quicheConfig.setDisableActiveMigration(true);
        quicheConfig.setVerifyPeer(false);

        // TODO review the meaning of those QuicheConfig settings and see if they match their HTTP2 equivalents
        quicheConfig.setMaxIdleTimeout(getHttpClient().getIdleTimeout());
        quicheConfig.setInitialMaxData((long)getInitialSessionRecvWindow());
        quicheConfig.setInitialMaxStreamDataBidiLocal((long)getInitialStreamRecvWindow());
        quicheConfig.setInitialMaxStreamDataUni((long)getInitialStreamRecvWindow());
        quicheConfig.setInitialMaxStreamsBidi((long)getMaxConcurrentPushedStreams());
        quicheConfig.setInitialMaxStreamsUni((long)getMaxConcurrentPushedStreams());

        super.doStart();
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        endPoint.setIdleTimeout(getHttpClient().getIdleTimeout());
        return connectionFactory.newConnection(endPoint, context);
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, protocol);
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new MultiplexHttpDestination(getHttpClient(), origin, true);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());
        @SuppressWarnings("unchecked")
        Promise<org.eclipse.jetty.client.api.Connection> promise = (Promise<org.eclipse.jetty.client.api.Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.connect(address, context);
    }
}
