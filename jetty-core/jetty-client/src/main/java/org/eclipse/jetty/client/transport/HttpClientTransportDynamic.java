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

package org.eclipse.jetty.client.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link HttpClientTransport} that can dynamically switch among different application protocols.</p>
 * <p>Applications create HttpClientTransportDynamic instances specifying all the <em>application protocols</em>
 * it supports, in order of preference. The typical case is when the server supports both HTTP/1.1 and
 * HTTP/2, but the client does not know that. In this case, the application will create a
 * HttpClientTransportDynamic in this way:</p>
 * <pre>{@code
 * ClientConnector clientConnector = new ClientConnector();
 * // Configure the clientConnector.
 *
 * // Prepare the application protocols.
 * ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
 * HTTP2Client http2Client = new HTTP2Client(clientConnector);
 * ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
 *
 * // Create the HttpClientTransportDynamic, preferring h2 over h1.
 * HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector, h2, h1);
 *
 * // Create the HttpClient.
 * client = new HttpClient(transport);
 * }</pre>
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
 * {@link HttpClientTransport#newOrigin(Request)}.</p>
 */
@ManagedObject("The HTTP client transport that supports many HTTP versions")
public class HttpClientTransportDynamic extends AbstractConnectorHttpClientTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransportDynamic.class);

    private final List<ClientConnectionFactory.Info> clientConnectionFactoryInfos;

    /**
     * Creates a dynamic transport that speaks only HTTP/1.1.
     */
    public HttpClientTransportDynamic()
    {
        this(new ClientConnector(), HttpClientConnectionFactory.HTTP11);
    }

    /**
     * <p>Creates a dynamic transport with the given {@link ClientConnector} and the given protocols,
     * in order of preference (first the most preferred).</p>
     *
     * @param connector the ClientConnector used by this transport
     * @param infos the <em>application protocols</em> that this transport can speak
     */
    public HttpClientTransportDynamic(ClientConnector connector, ClientConnectionFactory.Info... infos)
    {
        super(connector);
        this.clientConnectionFactoryInfos = infos.length == 0 ? List.of(HttpClientConnectionFactory.HTTP11) : List.of(infos);
        this.clientConnectionFactoryInfos.forEach(this::installBean);
        setConnectionPoolFactory(destination ->
            new MultiplexConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), 1)
        );
    }

    @Override
    public Origin newOrigin(Request request)
    {
        boolean secure = HttpScheme.isSecure(request.getScheme());

        List<Info> matchingInfos = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<String> excludedProtocols = (List<String>)request.getAttributes().get(Origin.Protocol.EXCLUDED_PROTOCOLS_ATTRIBUTE);
        excludedProtocols = excludedProtocols != null ? excludedProtocols : List.of();

        if (((HttpRequest)request).isVersionExplicit())
        {
            HttpVersion version = request.getVersion();
            List<String> wanted = toProtocols(version, secure);
            for (Info info : clientConnectionFactoryInfos)
            {
                // Find the first protocol that matches the version.
                for (String p : info.getProtocols(secure))
                {
                    if (excludedProtocols.contains(p))
                        break;
                    if (!wanted.contains(p))
                        continue;
                    matchingInfos.add(info);
                    break;
                }
                if (matchingInfos.isEmpty())
                    continue;
                break;
            }
        }
        else
        {
            for (Info info : clientConnectionFactoryInfos)
            {
                List<String> protocols = info.getProtocols(secure);
                if (protocols.isEmpty())
                    continue;
                if (excludedProtocols.stream().anyMatch(protocols::contains))
                    continue;
                matchingInfos.add(info);
                // Only pick the first non-secure because cannot negotiate.
                if (!secure)
                    break;
            }
        }

        if (matchingInfos.isEmpty())
        {
            if (request.getTransport() == null)
                request.transport(Transport.TCP_IP);
            return getHttpClient().createOrigin(request, null);
        }

        Info preferredInfo = matchingInfos.get(0);
        if (matchingInfos.size() > 1)
        {
            // Keep only the infos that have the same transport.
            matchingInfos = matchingInfos.stream()
                .filter(i -> i.getTransport().equals(preferredInfo.getTransport()))
                .toList();
        }

        boolean manyProtocols = matchingInfos.size() > 1 || preferredInfo.getProtocols(secure).size() > 1;
        boolean negotiate = secure && manyProtocols;

        List<String> protocols = new ArrayList<>();
        for (Info info : matchingInfos)
        {
            protocols.addAll(info.getProtocols(secure));
        }
        if (negotiate)
            protocols.remove("h2c");

        if (request.getTransport() == null)
            request.transport(preferredInfo.getTransport());

        return getHttpClient().createOrigin(request, new Origin.Protocol(protocols, negotiate));
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        return new HttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        String alpnProtocol = (String)context.get(ClientConnector.APPLICATION_PROTOCOL_CONTEXT_KEY);

        Info factoryInfo;
        if (alpnProtocol != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ALPN protocol {}", alpnProtocol);
            factoryInfo = findClientConnectionFactoryInfo(List.of(alpnProtocol), true)
                .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for negotiated protocol " + alpnProtocol));
        }
        else
        {
            // No ALPN, let's try to find the matching protocol.
            HttpDestination destination = (HttpDestination)context.get(Destination.CONTEXT_KEY);
            // In case of a forward proxy, the destination has been set to the proxy destination.
            Origin origin = destination.getOrigin();
            Origin.Protocol protocol = origin.getProtocol();
            List<String> protocols = protocol != null ? protocol.getProtocols() : List.of();
            factoryInfo = findClientConnectionFactoryInfo(protocols, origin.isSecure())
                .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for protocol " + protocol));
            if (LOG.isDebugEnabled())
                LOG.debug("No ALPN protocol, using {}", factoryInfo);
        }

        return factoryInfo.getClientConnectionFactory().newConnection(endPoint, context);
    }

    public void upgrade(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(Destination.CONTEXT_KEY);
        Origin origin = destination.getOrigin();
        Origin.Protocol protocol = origin.getProtocol();
        List<String> protocols = protocol != null ? protocol.getProtocols() : List.of();
        Info info = findClientConnectionFactoryInfo(protocols, origin.isSecure())
            .orElseThrow(() -> new IllegalStateException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " to upgrade to protocol " + protocol));
        info.upgrade(endPoint, context);
    }

    private Optional<Info> findClientConnectionFactoryInfo(List<String> protocols, boolean secure)
    {
        return clientConnectionFactoryInfos.stream()
            .filter(info -> info.matches(protocols, secure))
            .findFirst();
    }

    private List<String> toProtocols(HttpVersion version, boolean secure)
    {
        return switch (version)
        {
            case HTTP_0_9, HTTP_1_0, HTTP_1_1 -> List.of("http/1.1");
            case HTTP_2 -> secure ? List.of("h2c", "h2") : List.of("h2c");
            case HTTP_3 -> secure ? List.of("h3") : List.of();
        };
    }
}
