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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jetty.alpn.client.ALPNClientConnection;
import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
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
     * <p>Creates a dynamic transport that speaks the given protocols, in order of preference
     * (first the most preferred).</p>
     *
     * @param infos the protocols this dynamic transport speaks
     * @deprecated use {@link #HttpClientTransportDynamic(ClientConnector, ClientConnectionFactory.Info...)}
     */
    @Deprecated(since = "12.0.7", forRemoval = true)
    public HttpClientTransportDynamic(ClientConnectionFactory.Info... infos)
    {
        this(findClientConnector(infos), infos);
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

    private static ClientConnector findClientConnector(ClientConnectionFactory.Info[] infos)
    {
        return Arrays.stream(infos)
            .flatMap(info -> info.getContainedBeans(ClientConnector.class).stream())
            .findFirst()
            .orElseGet(ClientConnector::new);
    }

    @Override
    public Origin newOrigin(Request request)
    {
        boolean secure = HttpScheme.isSecure(request.getScheme());

        List<Info> matchingInfos = new ArrayList<>();
        boolean negotiate = false;

        if (((HttpRequest)request).isVersionExplicit())
        {
            HttpVersion version = request.getVersion();
            List<String> wanted = toProtocols(version);
            for (Info info : clientConnectionFactoryInfos)
            {
                // Find the first protocol that matches the version.
                List<String> protocols = info.getProtocols(secure);
                for (String p : protocols)
                {
                    if (wanted.contains(p))
                    {
                        matchingInfos.add(info);
                        break;
                    }
                }
                if (matchingInfos.isEmpty())
                    continue;

                if (secure && protocols.size() > 1)
                    negotiate = true;

                break;
            }
        }
        else
        {
            Info preferredInfo = clientConnectionFactoryInfos.get(0);
            if (secure)
            {
                if (preferredInfo.getProtocols(true).contains("h3"))
                {
                    // HTTP/3 is not compatible with HTTP/2 and HTTP/1
                    // due to UDP vs TCP, so we can only try HTTP/3.
                    matchingInfos.add(preferredInfo);
                }
                else
                {
                    // If the preferred protocol is not HTTP/3, then
                    // must be excluded since it won't be compatible
                    // with the other HTTP versions due to UDP vs TCP.
                    for (Info info : clientConnectionFactoryInfos)
                    {
                        if (info.getProtocols(true).contains("h3"))
                            continue;
                        matchingInfos.add(info);
                    }

                    // We can only have HTTP/1 and HTTP/2 here,
                    // decide whether negotiation is necessary.
                    if (!request.getHeaders().contains(HttpHeader.UPGRADE, "h2c"))
                    {
                        int matches = matchingInfos.size();
                        if (matches > 1)
                            negotiate = true;
                        else if (matches == 1)
                            negotiate = matchingInfos.get(0).getProtocols(true).size() > 1;
                    }
                }
            }
            else
            {
                // Pick the first that allows non-secure.
                for (Info info : clientConnectionFactoryInfos)
                {
                    if (info.getProtocols(false).contains("h3"))
                        continue;
                    matchingInfos.add(info);
                    break;
                }
            }
        }

        if (matchingInfos.isEmpty())
            return getHttpClient().createOrigin(request, null);

        Transport transport = request.getTransport();
        if (transport == null)
        {
            // Ask the ClientConnector for backwards compatibility
            // until ClientConnector.Configurator is removed.
            transport = getClientConnector().newTransport();
            if (transport == null)
                transport = matchingInfos.get(0).newTransport();
            request.transport(transport);
        }

        List<String> protocols = matchingInfos.stream()
            .flatMap(info -> info.getProtocols(secure).stream())
            .collect(Collectors.toCollection(ArrayList::new));
        if (negotiate)
            protocols.remove("h2c");
        Origin.Protocol protocol = new Origin.Protocol(protocols, negotiate);
        return getHttpClient().createOrigin(request, protocol);
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        return new HttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        Origin origin = destination.getOrigin();
        Origin.Protocol protocol = origin.getProtocol();
        ClientConnectionFactory factory;
        if (protocol == null)
        {
            // Use the default ClientConnectionFactory.
            factory = clientConnectionFactoryInfos.get(0).getClientConnectionFactory();
        }
        else
        {
            boolean intrinsicallySecure = origin.getTransport().isIntrinsicallySecure();
            if (!intrinsicallySecure && destination.isSecure() && protocol.isNegotiate())
            {
                factory = new ALPNClientConnectionFactory(getClientConnector().getExecutor(), this::newNegotiatedConnection, protocol.getProtocols());
            }
            else
            {
                factory = findClientConnectionFactoryInfo(protocol.getProtocols(), destination.isSecure())
                    .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for " + protocol))
                    .getClientConnectionFactory();
            }
        }
        return factory.newConnection(endPoint, context);
    }

    public void upgrade(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        Origin.Protocol protocol = destination.getOrigin().getProtocol();
        Info info = findClientConnectionFactoryInfo(protocol.getProtocols(), destination.isSecure())
            .orElseThrow(() -> new IllegalStateException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " to upgrade to " + protocol));
        info.upgrade(endPoint, context);
    }

    protected Connection newNegotiatedConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        try
        {
            ALPNClientConnection alpnConnection = (ALPNClientConnection)endPoint.getConnection();
            String protocol = alpnConnection.getProtocol();
            Info factoryInfo;
            if (protocol != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ALPN negotiated {} among {}", protocol, alpnConnection.getProtocols());
                List<String> protocols = List.of(protocol);
                factoryInfo = findClientConnectionFactoryInfo(protocols, true)
                    .orElseThrow(() -> new IOException("Cannot find " + ClientConnectionFactory.class.getSimpleName() + " for negotiated protocol " + protocol));
            }
            else
            {
                // Server does not support ALPN, let's try the first protocol.
                factoryInfo = clientConnectionFactoryInfos.get(0);
                if (LOG.isDebugEnabled())
                    LOG.debug("No ALPN protocol, using {}", factoryInfo);
            }
            return factoryInfo.getClientConnectionFactory().newConnection(endPoint, context);
        }
        catch (Throwable failure)
        {
            this.connectFailed(context, failure);
            throw failure;
        }
    }

    private Optional<Info> findClientConnectionFactoryInfo(List<String> protocols, boolean secure)
    {
        return clientConnectionFactoryInfos.stream()
            .filter(info -> info.matches(protocols, secure))
            .findFirst();
    }

    private List<String> toProtocols(HttpVersion version)
    {
        return switch (version)
        {
            case HTTP_0_9, HTTP_1_0, HTTP_1_1 -> List.of("http/1.1");
            case HTTP_2 -> List.of("h2c", "h2");
            case HTTP_3 -> List.of("h3");
        };
    }
}
