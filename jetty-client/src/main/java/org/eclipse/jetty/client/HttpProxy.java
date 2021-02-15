//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpProxy extends ProxyConfiguration.Proxy
{
    private static final Logger LOG = Log.getLogger(HttpProxy.class);

    public HttpProxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public HttpProxy(Origin.Address address, boolean secure)
    {
        super(address, secure);
    }

    public HttpProxy(Origin.Address address, SslContextFactory.Client sslContextFactory)
    {
        super(address, sslContextFactory);
    }

    @Override
    public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new HttpProxyClientConnectionFactory(connectionFactory);
    }

    @Override
    public URI getURI()
    {
        String scheme = isSecure() ? HttpScheme.HTTPS.asString() : HttpScheme.HTTP.asString();
        return URI.create(new Origin(scheme, getAddress()).asString());
    }

    private static class HttpProxyClientConnectionFactory implements ClientConnectionFactory
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
            SslContextFactory sslContextFactory = destination.getHttpClient().getSslContextFactory();
            if (destination.isSecure())
            {
                if (sslContextFactory != null)
                {
                    @SuppressWarnings("unchecked")
                    Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
                    Promise<Connection> wrapped = promise;
                    if (promise instanceof Promise.Wrapper)
                        wrapped = ((Promise.Wrapper<Connection>)promise).unwrap();
                    if (wrapped instanceof TunnelPromise)
                    {
                        ((TunnelPromise)wrapped).setEndPoint(endPoint);
                        return connectionFactory.newConnection(endPoint, context);
                    }
                    else
                    {
                        // Replace the promise with the proxy promise that creates the tunnel to the server.
                        CreateTunnelPromise tunnelPromise = new CreateTunnelPromise(connectionFactory, endPoint, promise, context);
                        context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, tunnelPromise);
                        return connectionFactory.newConnection(endPoint, context);
                    }
                }
                else
                {
                    throw new IOException("Cannot tunnel request, missing " +
                        SslContextFactory.class.getName() + " in " + HttpClient.class.getName());
                }
            }
            else
            {
                return connectionFactory.newConnection(endPoint, context);
            }
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
            if (proxy != null && proxy.isSecure())
                connect.scheme(HttpScheme.HTTPS.asString());

            final HttpConversation conversation = ((HttpRequest)connect).getConversation();
            conversation.setAttribute(EndPoint.class.getName(), endPoint);

            connect.attribute(Connection.class.getName(), new ProxyConnection(destination, connection, promise));

            connection.send(connect, result ->
            {
                // The EndPoint may have changed during the conversation, get the latest.
                EndPoint endPoint = (EndPoint)conversation.getAttribute(EndPoint.class.getName());
                if (result.isSucceeded())
                {
                    Response response = result.getResponse();
                    if (response.getStatus() == HttpStatus.OK_200)
                    {
                        tunnelSucceeded(endPoint);
                    }
                    else
                    {
                        HttpResponseException failure = new HttpResponseException("Unexpected " + response +
                            " for " + result.getRequest(), response);
                        tunnelFailed(endPoint, failure);
                    }
                }
                else
                {
                    tunnelFailed(endPoint, result.getFailure());
                }
            });
        }

        private void tunnelSucceeded(EndPoint endPoint)
        {
            try
            {
                // Replace the promise back with the original
                context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, promise);
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                ClientConnectionFactory sslConnectionFactory = destination.newSslClientConnectionFactory(null, connectionFactory);
                HttpConnectionOverHTTP oldConnection = (HttpConnectionOverHTTP)endPoint.getConnection();
                context.put(SslClientConnectionFactory.SSL_PEER_HOST_CONTEXT_KEY, destination.getHost());
                context.put(SslClientConnectionFactory.SSL_PEER_PORT_CONTEXT_KEY, destination.getPort());
                org.eclipse.jetty.io.Connection newConnection = sslConnectionFactory.newConnection(endPoint, context);
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

        private void setEndPoint(EndPoint endPoint)
        {
            HttpConversation conversation = ((HttpRequest)request).getConversation();
            conversation.setAttribute(EndPoint.class.getName(), endPoint);
        }
    }
}
