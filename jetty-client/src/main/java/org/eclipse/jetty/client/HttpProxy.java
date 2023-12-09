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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProxy extends ProxyConfiguration.Proxy
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpProxy.class);

    public HttpProxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public HttpProxy(Origin.Address address, boolean secure)
    {
        this(address, secure, null, new Origin.Protocol(List.of("http/1.1"), false));
    }

    public HttpProxy(Origin.Address address, boolean secure, Origin.Protocol protocol)
    {
        this(address, secure, null, Objects.requireNonNull(protocol));
    }

    public HttpProxy(Origin.Address address, SslContextFactory.Client sslContextFactory)
    {
        this(address, true, sslContextFactory, new Origin.Protocol(List.of("http/1.1"), false));
    }

    public HttpProxy(Origin.Address address, SslContextFactory.Client sslContextFactory, Origin.Protocol protocol)
    {
        this(address, true, sslContextFactory, Objects.requireNonNull(protocol));
    }

    private HttpProxy(Origin.Address address, boolean secure, SslContextFactory.Client sslContextFactory, Origin.Protocol protocol)
    {
        super(address, secure, sslContextFactory, Objects.requireNonNull(protocol));
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new HttpProxyClientConnectionFactory(connectionFactory);
    }

    @Override
    public URI getURI()
    {
        return URI.create(getOrigin().asString());
    }

    boolean requiresTunnel(Origin serverOrigin)
    {
        if (HttpClient.isSchemeSecure(serverOrigin.getScheme()))
            return true;
        Origin.Protocol serverProtocol = serverOrigin.getProtocol();
        if (serverProtocol == null)
            return true;
        List<String> serverProtocols = serverProtocol.getProtocols();
        return getProtocol().getProtocols().stream().noneMatch(p -> protocolMatches(p, serverProtocols));
    }

    private boolean protocolMatches(String protocol, List<String> protocols)
    {
        return protocols.stream().anyMatch(p -> protocol.equalsIgnoreCase(p) || (isHTTP2(p) && isHTTP2(protocol)));
    }

    private boolean isHTTP2(String protocol)
    {
        return "h2".equalsIgnoreCase(protocol) || "h2c".equalsIgnoreCase(protocol);
    }

    private class HttpProxyClientConnectionFactory implements ClientConnectionFactory
    {
        private final ClientConnectionFactory connectionFactory;

        private HttpProxyClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            if (requiresTunnel(destination.getOrigin()))
                return newProxyConnection(endPoint, context);
            else
                return connectionFactory.newConnection(endPoint, context);
        }

        private org.eclipse.jetty.io.Connection newProxyConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            // Replace the destination with the proxy destination.
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            HttpClient client = destination.getHttpClient();
            HttpDestination proxyDestination = client.resolveDestination(getOrigin());
            context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, proxyDestination);

            // Replace the promise with the proxy promise that creates the tunnel to the server.
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            CreateTunnelPromise tunnelPromise = new CreateTunnelPromise(connectionFactory, endPoint, destination, promise, context);
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, tunnelPromise);

            return connectionFactory.newConnection(endPoint, context);
        }
    }

    /**
     * <p>Creates a tunnel using HTTP CONNECT.</p>
     * <p>It is implemented as a promise because it needs to establish the
     * tunnel after the TCP connection is succeeded, and needs to notify
     * the nested promise when the tunnel is established (or failed).</p>
     */
    private static class CreateTunnelPromise implements Promise<Connection>
    {
        private final ClientConnectionFactory connectionFactory;
        private final EndPoint endPoint;
        private final HttpDestination destination;
        private final Promise<Connection> promise;
        private final Map<String, Object> context;

        private CreateTunnelPromise(ClientConnectionFactory connectionFactory, EndPoint endPoint, HttpDestination destination, Promise<Connection> promise, Map<String, Object> context)
        {
            this.connectionFactory = connectionFactory;
            this.endPoint = endPoint;
            this.destination = destination;
            this.promise = promise;
            this.context = context;
        }

        @Override
        public void succeeded(Connection connection)
        {
            // Replace the destination back with the original.
            context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, destination);
            // Replace the promise back with the original.
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, promise);
            tunnel(connection);
        }

        @Override
        public void failed(Throwable x)
        {
            tunnelFailed(endPoint, x);
        }

        private void tunnel(Connection connection)
        {
            String target = destination.getOrigin().getAddress().asString();
            HttpClient httpClient = destination.getHttpClient();
            long connectTimeout = httpClient.getConnectTimeout();
            Request connect = new TunnelRequest(httpClient, destination.getProxy().getURI())
                .path(target)
                .headers(headers -> headers.put(HttpHeader.HOST, target))
                // Use the connect timeout as a total timeout,
                // since this request is to "connect" to the server.
                .timeout(connectTimeout, TimeUnit.MILLISECONDS);

            HttpDestination proxyDestination = httpClient.resolveDestination(destination.getProxy().getOrigin());
            connect.attribute(Connection.class.getName(), new ProxyConnection(proxyDestination, connection, promise));
            connection.send(connect, new TunnelListener(connect));
        }

        private void tunnelSucceeded(EndPoint endPoint)
        {
            try
            {
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                ClientConnectionFactory factory = connectionFactory;
                if (destination.isSecure())
                {
                    // Don't want to do DNS resolution here.
                    InetSocketAddress address = InetSocketAddress.createUnresolved(destination.getHost(), destination.getPort());
                    context.put(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, address);
                    factory = destination.newSslClientConnectionFactory(null, factory);
                }
                var oldConnection = endPoint.getConnection();
                var newConnection = factory.newConnection(endPoint, context);
                if (LOG.isDebugEnabled())
                    LOG.debug("HTTP tunnel established: {} over {}", oldConnection, newConnection);
                endPoint.upgrade(newConnection);
            }
            catch (Throwable x)
            {
                tunnelFailed(endPoint, x);
            }
        }

        private void tunnelFailed(EndPoint endPoint, Throwable failure)
        {
            endPoint.close(failure);
            promise.failed(failure);
        }

        private class TunnelListener extends Response.Listener.Adapter
        {
            private final HttpConversation conversation;

            private TunnelListener(Request request)
            {
                this.conversation = ((HttpRequest)request).getConversation();
            }

            @Override
            public void onHeaders(Response response)
            {
                // The EndPoint may have changed during the conversation, get the latest.
                EndPoint endPoint = (EndPoint)conversation.getAttribute(EndPoint.class.getName());
                if (response.getStatus() == HttpStatus.OK_200)
                {
                    tunnelSucceeded(endPoint);
                }
                else
                {
                    HttpResponseException failure = new HttpResponseException("Unexpected " + response + " for " + response.getRequest(), response);
                    tunnelFailed(endPoint, failure);
                }
            }

            @Override
            public void onComplete(Result result)
            {
                if (result.isFailed())
                    tunnelFailed(endPoint, result.getFailure());
            }
        }
    }

    private static class ProxyConnection implements Connection, Attachable
    {
        private final Destination destination;
        private final Connection connection;
        private final Promise<Connection> promise;
        private Object attachment;

        private ProxyConnection(Destination destination, Connection connection, Promise<Connection> promise)
        {
            this.destination = destination;
            this.connection = connection;
            this.promise = promise;
        }

        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return connection.getLocalSocketAddress();
        }

        @Override
        public SocketAddress getRemoteSocketAddress()
        {
            return connection.getRemoteSocketAddress();
        }

        @Override
        public void send(Request request, Response.CompleteListener listener)
        {
            if (connection.isClosed())
            {
                destination.newConnection(new TunnelPromise(request, listener, promise));
            }
            else
            {
                connection.send(request, listener);
            }
        }

        @Override
        public void close()
        {
            connection.close();
        }

        @Override
        public boolean isClosed()
        {
            return connection.isClosed();
        }

        @Override
        public void setAttachment(Object obj)
        {
            this.attachment = obj;
        }

        @Override
        public Object getAttachment()
        {
            return attachment;
        }
    }

    private static class TunnelPromise implements Promise<Connection>
    {
        private final Request request;
        private final Response.CompleteListener listener;
        private final Promise<Connection> promise;

        private TunnelPromise(Request request, Response.CompleteListener listener, Promise<Connection> promise)
        {
            this.request = request;
            this.listener = listener;
            this.promise = promise;
        }

        @Override
        public void succeeded(Connection connection)
        {
            connection.send(request, listener);
        }

        @Override
        public void failed(Throwable x)
        {
            promise.failed(x);
        }
    }

    public static class TunnelRequest extends HttpRequest
    {
        private final URI proxyURI;

        private TunnelRequest(HttpClient client, URI proxyURI)
        {
            this(client, new HttpConversation(), proxyURI);
        }

        private TunnelRequest(HttpClient client, HttpConversation conversation, URI proxyURI)
        {
            super(client, conversation, proxyURI);
            this.proxyURI = proxyURI;
            method(HttpMethod.CONNECT);
        }

        @Override
        HttpRequest copyInstance(URI newURI)
        {
            return new TunnelRequest(getHttpClient(), getConversation(), newURI);
        }

        @Override
        public URI getURI()
        {
            return proxyURI;
        }
    }
}
