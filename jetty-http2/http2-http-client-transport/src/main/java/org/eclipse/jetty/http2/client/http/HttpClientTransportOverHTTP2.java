//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@ManagedObject("The HTTP/2 client transport")
public class HttpClientTransportOverHTTP2 extends ContainerLifeCycle implements HttpClientTransport
{
    private final HTTP2Client client;
    private ClientConnectionFactory connectionFactory;
    private HttpClient httpClient;

    public HttpClientTransportOverHTTP2(HTTP2Client client)
    {
        this.client = client;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return client.getSelectors();
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!client.isStarted())
        {
            client.setExecutor(httpClient.getExecutor());
            client.setScheduler(httpClient.getScheduler());
            client.setByteBufferPool(httpClient.getByteBufferPool());
            client.setConnectTimeout(httpClient.getConnectTimeout());
            client.setIdleTimeout(httpClient.getIdleTimeout());
        }
        addBean(client);
        super.doStart();

        this.connectionFactory = new HTTP2ClientConnectionFactory();
        client.setClientConnectionFactory((endPoint, context) ->
        {
            HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
            return destination.getClientConnectionFactory().newConnection(endPoint, context);
        });
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(client);
    }

    @Override
    public void setHttpClient(HttpClient client)
    {
        httpClient = client;
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new HttpDestinationOverHTTP2(httpClient, origin);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        client.setConnectTimeout(httpClient.getConnectTimeout());

        final HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        final Promise<Connection> connectionPromise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);

        SessionListenerPromise listenerPromise = new SessionListenerPromise(destination, connectionPromise);

        SslContextFactory sslContextFactory = null;
        if (HttpScheme.HTTPS.is(destination.getScheme()))
            sslContextFactory = httpClient.getSslContextFactory();

        client.connect(sslContextFactory, address, listenerPromise, listenerPromise, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        ClientConnectionFactory factory = connectionFactory;
        SslContextFactory sslContextFactory = (SslContextFactory)context.get(SslClientConnectionFactory.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
        if (sslContextFactory != null)
            factory = new ALPNClientConnectionFactory(client.getExecutor(), factory, client.getProtocols());
        return factory.newConnection(endPoint, context);
    }

    protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
    {
        return new HttpConnectionOverHTTP2(destination, session);
    }

    private class SessionListenerPromise extends Session.Listener.Adapter implements Promise<Session>
    {
        private final HttpDestination destination;
        private final Promise<Connection> promise;
        private HttpConnectionOverHTTP2 connection;

        public SessionListenerPromise(HttpDestination destination, Promise<Connection> promise)
        {
            this.destination = destination;
            this.promise = promise;
        }

        @Override
        public void succeeded(Session session)
        {
            connection = newHttpConnection(destination, session);
            promise.succeeded(connection);
        }

        @Override
        public void failed(Throwable failure)
        {
            promise.failed(failure);
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame)
        {
            connection.close();
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            connection.close(failure);
        }
    }
}
