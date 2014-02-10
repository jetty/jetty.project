//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpProxy extends ProxyConfiguration.Proxy
{
    public HttpProxy(String host, int port)
    {
        this(new Origin.Address(host, port), false);
    }

    public HttpProxy(Origin.Address address, boolean secure)
    {
        super(address, secure);
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

    public static class HttpProxyClientConnectionFactory implements ClientConnectionFactory
    {
        private static final Logger LOG = Log.getLogger(HttpProxyClientConnectionFactory.class);
        private final ClientConnectionFactory connectionFactory;

        public HttpProxyClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            final ProxyPromise proxyPromise = new ProxyPromise(endPoint, promise, context);
            // Replace the promise with the proxy one
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, proxyPromise);
            return connectionFactory.newConnection(endPoint, context);
        }

        /**
         * Decides whether to establish a proxy tunnel using HTTP CONNECT.
         * It is implemented as a promise because it needs to establish the
         * tunnel after the TCP connection is succeeded, and needs to notify
         * the nested promise when the tunnel is established (or failed).
         */
        private class ProxyPromise implements Promise<Connection>
        {
            private final EndPoint endPoint;
            private final Promise<Connection> promise;
            private final Map<String, Object> context;

            private ProxyPromise(EndPoint endPoint, Promise<Connection> promise, Map<String, Object> context)
            {
                this.endPoint = endPoint;
                this.promise = promise;
                this.context = context;
            }

            @Override
            public void succeeded(Connection connection)
            {
                HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                if (HttpScheme.HTTPS.is(destination.getScheme()))
                {
                    SslContextFactory sslContextFactory = destination.getHttpClient().getSslContextFactory();
                    if (sslContextFactory != null)
                    {
                        tunnel(destination, connection);
                    }
                    else
                    {
                        String message = String.format("Cannot perform requests over SSL, no %s in %s",
                                SslContextFactory.class.getSimpleName(), HttpClient.class.getSimpleName());
                        promise.failed(new IllegalStateException(message));
                    }
                }
                else
                {
                    promise.succeeded(connection);
                }
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }

            private void tunnel(HttpDestination destination, final Connection connection)
            {
                String target = destination.getOrigin().getAddress().asString();
                Origin.Address proxyAddress = destination.getConnectAddress();
                HttpClient httpClient = destination.getHttpClient();
                Request connect = httpClient.newRequest(proxyAddress.getHost(), proxyAddress.getPort())
                        .scheme(HttpScheme.HTTP.asString())
                        .method(HttpMethod.CONNECT)
                        .path(target)
                        .header(HttpHeader.HOST, target)
                        .timeout(httpClient.getConnectTimeout(), TimeUnit.MILLISECONDS);

                connection.send(connect, new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isFailed())
                        {
                            tunnelFailed(result.getFailure());
                        }
                        else
                        {
                            Response response = result.getResponse();
                            if (response.getStatus() == 200)
                            {
                                tunnelSucceeded();
                            }
                            else
                            {
                                tunnelFailed(new HttpResponseException("Received " + response + " for " + result.getRequest(), response));
                            }
                        }
                    }
                });
            }

            private void tunnelSucceeded()
            {
                try
                {
                    // Replace the promise back with the original
                    context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, promise);
                    HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
                    HttpClient client = destination.getHttpClient();
                    ClientConnectionFactory sslConnectionFactory = new SslClientConnectionFactory(client.getSslContextFactory(), client.getByteBufferPool(), client.getExecutor(), connectionFactory);
                    HttpConnectionOverHTTP oldConnection = (HttpConnectionOverHTTP)endPoint.getConnection();
                    org.eclipse.jetty.io.Connection newConnection = sslConnectionFactory.newConnection(endPoint, context);
                    Helper.replaceConnection(oldConnection, newConnection);
                    // Avoid setting fill interest in the old Connection,
                    // without closing the underlying EndPoint.
                    oldConnection.softClose();
                    LOG.debug("HTTP tunnel established: {} over {}", oldConnection, newConnection);
                }
                catch (Throwable x)
                {
                    tunnelFailed(x);
                }
            }

            private void tunnelFailed(Throwable failure)
            {
                endPoint.close();
                failed(failure);
            }
        }
    }
}
