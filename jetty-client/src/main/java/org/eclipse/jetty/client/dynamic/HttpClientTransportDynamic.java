//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.dynamic;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * <p>A {@link HttpClientTransport} that can dynamically switch among different application protocols.</p>
 * <p>Applications create HttpClientTransportDynamic instances specifying all the <em>application protocols</em>
 * it supports, in order of preference. The typical case is when the server supports both HTTP/1.1 and
 * HTTP/2, but the client does not know that. In this case, the application will create a
 * HttpClientTransportDynamic in this way:</p>
 * <pre>
 * ClientConnector clientConnector = new ClientConnector();
 * // Configure the clientConnector.
 *
 * // Prepare the application protocols.
 * ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP;
 * HTTP2Client http2Client = new HTTP2Client(clientConnector);
 * ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
 *
 * // Create the HttpClientTransportDynamic, preferring h2 over h1.
 * HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector, h2, h1);
 *
 * // Create the HttpClient.
 * client = new HttpClient(transport);
 * </pre>
 * <p>Note how in the code above the HttpClientTransportDynamic has been created with the <em>application
 * protocols</em> {@code h2} and {@code h1}, without the need to specify TLS (which is implied by the request
 * scheme) or ALPN (which is implied by HTTP/2 over TLS).</p>
 * <p>When a request is first sent, {@code (scheme, host, port)} are not enough to identify the destination
 * because the same origin may speak different protocols.
 * For example, the Jetty server supports speaking clear-text {@code http/1.1} and {@code h2c} on the same port.
 * Imagine a client sending a {@code h2c} request to that port; this will create a destination and connections
 * that speak {@code h2c}; it won't be possible to use the connections from that destination to send
 * {@code http/1.1} requests.
 * Therefore a destination is identified by a {@link org.eclipse.jetty.client.Origin} and
 * applications can customize the creation of the origin (for example depending on request protocol
 * version, or request headers, or request attributes, or even request path) by overriding
 * {@link HttpClientTransport#newOrigin(HttpRequest)}.</p>
 */
public class HttpClientTransportDynamic extends AbstractConnectorHttpClientTransport
{
    private final List<ClientConnectionFactory.Info> factoryInfos;
    private final List<String> protocols;

    /**
     * Creates a transport that speaks only HTTP/1.1.
     */
    public HttpClientTransportDynamic()
    {
        this(new ClientConnector(), HttpClientConnectionFactory.HTTP11);
    }

    /**
     * Creates a transport with the given {@link ClientConnector} and the given <em>application protocols</em>.
     *
     * @param connector the ClientConnector used by this transport
     * @param factoryInfos the <em>application protocols</em> that this transport can speak
     */
    public HttpClientTransportDynamic(ClientConnector connector, ClientConnectionFactory.Info... factoryInfos)
    {
        super(connector);
        addBean(connector);
        if (factoryInfos.length == 0)
            factoryInfos = new Info[]{HttpClientConnectionFactory.HTTP11};
        this.factoryInfos = Arrays.asList(factoryInfos);
        this.protocols = Arrays.stream(factoryInfos)
                .flatMap(info -> info.getProtocols().stream())
                .distinct()
                .map(p -> p.toLowerCase(Locale.ENGLISH))
                .collect(Collectors.toList());
        Arrays.stream(factoryInfos).forEach(this::addBean);
        setConnectionPoolFactory(destination ->
                new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1));
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        boolean ssl = HttpClient.isSchemeSecure(request.getScheme());
        String http1 = "http/1.1";
        String http2 = ssl ? "h2" : "h2c";
        List<String> protocols = List.of();
        if (request.isVersionExplicit())
        {
            HttpVersion version = request.getVersion();
            String desired = version == HttpVersion.HTTP_2 ? http2 : http1;
            if (this.protocols.contains(desired))
                protocols = List.of(desired);
        }
        else
        {
            if (ssl)
            {
                // There may be protocol negotiation, so preserve the order
                // of protocols chosen by the application.
                // We need to keep multiple protocols in case the protocol
                // is negotiated: e.g. [http/1.1, h2] negotiates [h2], but
                // here we don't know yet what will be negotiated.
                protocols = this.protocols.stream()
                    .filter(p -> p.equals(http1) || p.equals(http2))
                    .collect(Collectors.toList());
            }
            else
            {
                // Pick the first.
                protocols = List.of(this.protocols.get(0));
            }
        }
        Origin.Protocol protocol = null;
        if (!protocols.isEmpty())
            protocol = new Origin.Protocol(protocols, ssl && protocols.contains(http2));
        return getHttpClient().createOrigin(request, protocol);
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new MultiplexHttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        Origin.Protocol protocol = destination.getOrigin().getProtocol();
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

    public void upgrade(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        Origin.Protocol protocol = destination.getOrigin().getProtocol();
        Info info = findClientConnectionFactoryInfo(protocol.getProtocols())
            .orElseThrow(() -> new IllegalStateException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " to upgrade to " + protocol));
        info.upgrade(endPoint, context);
    }

    protected Connection newNegotiatedConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        try
        {
            ALPNClientConnection alpnConnection = (ALPNClientConnection)endPoint.getConnection();
            String protocol = alpnConnection.getProtocol();
            if (LOG.isDebugEnabled())
                LOG.debug("ALPN negotiated {} among {}", protocol, alpnConnection.getProtocols());
            if (protocol == null)
                throw new IOException("Could not negotiate protocol among " + alpnConnection.getProtocols());
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
