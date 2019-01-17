//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.dynamic;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

public class HttpClientTransportDynamic extends AbstractConnectorHttpClientTransport implements HttpClientTransport.Dynamic
{
    private final List<ClientConnectionFactory.Info> factoryInfos;

    public HttpClientTransportDynamic()
    {
        this(new ClientConnector(), HttpClientConnectionFactory.HTTP);
    }

    public HttpClientTransportDynamic(ClientConnector connector, ClientConnectionFactory.Info... factoryInfos)
    {
        super(connector);
        addBean(connector);
        if (factoryInfos.length == 0)
            throw new IllegalArgumentException("Missing ClientConnectionFactory");
        this.factoryInfos = Arrays.asList(factoryInfos);
        for (ClientConnectionFactory.Info factoryInfo : factoryInfos)
            addBean(factoryInfo);
        setConnectionPoolFactory(destination ->
                new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1));
    }

    @Override
    public HttpDestination.Protocol getProtocol(HttpRequest request)
    {
        HttpVersion version = request.getVersion();
        if (HttpScheme.HTTPS.is(request.getScheme()))
        {
            List<String> protocols = version == HttpVersion.HTTP_2 ? List.of("h2") : List.of("h2", "http/1.1");
            if (findClientConnectionFactoryInfo(protocols).isPresent())
                return new HttpDestination.Protocol(protocols, true);
        }
        else
        {
            List<String> protocols = version == HttpVersion.HTTP_2 ? List.of("h2c") : List.of("http/1.1", "h2c");
            if (findClientConnectionFactoryInfo(protocols).isPresent())
                return new HttpDestination.Protocol(protocols, false);
        }
        return null;
    }

    @Override
    public HttpDestination newHttpDestination(HttpDestination.Info info)
    {
        return new MultiplexHttpDestination(getHttpClient(), info);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        HttpDestination.Protocol protocol = destination.getInfo().getProtocol();
        ClientConnectionFactory.Info factoryInfo;
        if (protocol == null)
        {
            // Use the default ClientConnectionFactory.
            factoryInfo = factoryInfos.get(0);
        }
        else
        {
            if (destination.isSecure() && protocol.isNegotiate())
            {
                factoryInfo = new ALPNClientConnectionFactory.ALPN(getClientConnector().getExecutor(), this::newNegotiatedConnection, protocol.getProtocols());
            }
            else
            {
                factoryInfo = findClientConnectionFactoryInfo(protocol.getProtocols())
                        .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for " + protocol));
            }
        }
        return factoryInfo.getClientConnectionFactory().newConnection(endPoint, context);
    }

    protected Connection newNegotiatedConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        try
        {
            ALPNClientConnection alpnConnection = (ALPNClientConnection)endPoint.getConnection();
            String protocol = alpnConnection.getProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("ALPN negotiated {} among {}", protocol, alpnConnection.getProtocols());
            List<String> protocols = List.of(protocol);
            Info factoryInfo = findClientConnectionFactoryInfo(protocols)
                    .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for negotiated protocol " + protocol));
            return factoryInfo.getClientConnectionFactory().newConnection(endPoint, context);
        }
        catch (Throwable failure)
        {
            this.connectFailed(context, failure);
            throw failure;
        }
    }

    private Optional<Info> findClientConnectionFactoryInfo(List<String> protocols)
    {
        return factoryInfos.stream()
                .filter(info -> info.matches(protocols))
                .findFirst();
    }
}
