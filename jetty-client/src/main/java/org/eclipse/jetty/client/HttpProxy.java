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

package org.eclipse.jetty.client;

import java.io.IOException;
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
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpProxy extends ProxyConfiguration.Proxy
{
    private static final Logger LOG = Log.getLogger(HttpProxy.class);

    public HttpProxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public HttpProxy(Origin.Address address, boolean secure)
    {
        this(address, secure, new HttpDestination.Protocol(List.of("http/1.1"), false));
    }

    public HttpProxy(Origin.Address address, boolean secure, HttpDestination.Protocol protocol)
    {
        super(address, secure, Objects.requireNonNull(protocol));
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new HttpProxyClientConnectionFactory(connectionFactory);
    }

    @Override
    public URI getURI()
    {
        return URI.create(newOrigin().asString());
    }

    private Origin newOrigin()
    {
        String scheme = isSecure() ? HttpScheme.HTTPS.asString() : HttpScheme.HTTP.asString();
        return new Origin(scheme, getAddress());
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
            HttpDestination.Protocol serverProtocol = destination.getKey().getProtocol();
            boolean sameProtocol = proxySpeaksServerProtocol(serverProtocol);
            if (destination.isSecure() || !sameProtocol)
            {
                @SuppressWarnings("unchecked")
                Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
                Promise<Connection> wrapped = promise;
                if (promise instanceof Promise.Wrapper)
                    wrapped = ((Promise.Wrapper<Connection>)promise).unwrap();
                if (wrapped instanceof TunnelPromise)
                {
                    // TODO: this is very so weird that it deserves a comment.
                    //  I don't even know when this happens.
                    ((TunnelPromise)wrapped).setEndPoint(endPoint);
                    return connectionFactory.newConnection(endPoint, context);
                }
                else
                {
                    return newProxyConnection(endPoint, context);
                }
            }
            else
            {
                return connectionFactory.newConnection(endPoint, context);
            }
        }

        private boolean proxySpeaksServerProtocol(HttpDestination.Protocol serverProtocol)
        {
            return serverProtocol != null && getProtocol().getProtocols().stream().anyMatch(p -> serverProtocol.getProtocols().stream().anyMatch(p::equalsIgnoreCase));
        }

        private org.eclipse.jetty.io.Connection newProxyConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            // Replace the promise with the proxy promise that creates the tunnel to the server.
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            CreateTunnelPromise tunnelPromise = new CreateTunnelPromise(connectionFactory, endPoint, promise, context);
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, tunnelPromise);

            // Replace the destination with the proxy destination.
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            HttpClient client = destination.getHttpClient();
            HttpDestination proxyDestination = client.resolveDestination(new HttpDestination.Key(newOrigin(), getProtocol()));
            context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, proxyDestination);
            try
            {
                return connectionFactory.newConnection(endPoint, context);
            }
            finally
            {
                context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, destination);
            }
        }
    }

    /**
     * <p>Creates a tunnel using HTTP CONNECT.</p>
     * <p>It is implemented as a promise because it needs to establish the
     * tunnel after the TCP connection is succeeded, and needs to notify
     * the nested promise when the tunnel is established (or failed).</p>
     */
    private class CreateTunnelPromise implements Promise<Connection>
    {
        private final ClientConnectionFactory connectionFactory;
        private final EndPoint endPoint;
        private final Promise<Connection> promise;
        private final Map<String, Object> context;

        private CreateTunnelPromise(ClientConnectionFactory connectionFactory, EndPoint endPoint, Promise<Connection> promise, Map<String, Object> context)
        {
            this.connectionFactory = connectionFactory;
            this.endPoint = endPoint;
            this.promise = promise;
            this.context = context;
        }

        @Override
        public void succeeded(Connection connection)
        {
            // Replace the promise back with the original.
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, promise);
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            tunnel(destination, connection);
        }

        @Override
        public void failed(Throwable x)
        {
            tunnelFailed(endPoint, x);
        }

        private void tunnel(HttpDestination destination, Connection connection)
        {
            String target = destination.getOrigin().getAddress().asString();
            Origin.Address proxyAddress = destination.getConnectAddress();
            HttpClient httpClient = destination.getHttpClient();
            long connectTimeout = httpClient.getConnectTimeout();
            Request connect = httpClient.newRequest(proxyAddress.getHost(), proxyAddress.getPort())
                    .method(HttpMethod.CONNECT)
                    .path(target)
                    .header(HttpHeader.HOST, target)
                    .idleTimeout(2 * connectTimeout, TimeUnit.MILLISECONDS)
                    .timeout(connectTimeout, TimeUnit.MILLISECONDS);
            ProxyConfiguration.Proxy proxy = destination.getProxy();
            if (proxy.isSecure())
                connect.scheme(HttpScheme.HTTPS.asString());

            HttpConversation conversation = ((HttpRequest)connect).getConversation();
            conversation.setAttribute(EndPoint.class.getName(), endPoint);

            connect.attribute(Connection.class.getName(), new ProxyConnection(destination, connection, promise));

            connection.send(connect, new TunnelListener(conversation));
        }

        private void tunnelSucceeded(EndPoint endPoint)
        {
            try
            {
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                ClientConnectionFactory connectionFactory = this.connectionFactory;
                if (destination.isSecure())
                    connectionFactory = destination.newSslClientConnectionFactory(connectionFactory);
                var oldConnection = endPoint.getConnection();
                var newConnection = connectionFactory.newConnection(endPoint, context);
                // TODO: the comment below is outdated: we only create the connection and not link it to the endPoint.
                // Creating the connection will link the new Connection the EndPoint,
                // but we need the old Connection linked for the upgrade to do its job.
                endPoint.setConnection(oldConnection);
                endPoint.upgrade(newConnection);
                if (LOG.isDebugEnabled())
                    LOG.debug("HTTP tunnel established: {} over {}", oldConnection, newConnection);
            }
            catch (Throwable x)
            {
                tunnelFailed(endPoint, x);
            }
        }

        private void tunnelFailed(EndPoint endPoint, Throwable failure)
        {
            endPoint.close();
            promise.failed(failure);
        }

        private class TunnelListener extends Response.Listener.Adapter
        {
            private final HttpConversation conversation;

            private TunnelListener(HttpConversation conversation)
            {
                this.conversation = conversation;
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
                    HttpResponseException failure = new HttpResponseException("Unexpected " + response +
                            " for " + response.getRequest(), response);
                    tunnelFailed(endPoint, failure);
                }
            }

            @Override
            public void onComplete(Result result)
            {
                // TODO: do we need this? For timeouts, I/O failures, etc?
                if (!result.isSucceeded())
                    tunnelFailed(endPoint, result.getFailure());
            }
        }
    }

    private class ProxyConnection implements Connection
    {
        private final Destination destination;
        private final Connection connection;
        private final Promise<Connection> promise;

        private ProxyConnection(Destination destination, Connection connection, Promise<Connection> promise)
        {
            this.destination = destination;
            this.connection = connection;
            this.promise = promise;
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
    }

    private class TunnelPromise implements Promise<Connection>
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

        private void setEndPoint(EndPoint endPoint)
        {
            HttpConversation conversation = ((HttpRequest)request).getConversation();
            conversation.setAttribute(EndPoint.class.getName(), endPoint);
        }
    }
}
