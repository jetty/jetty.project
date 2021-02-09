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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@ManagedObject("The HTTP/2 client transport")
public class HttpClientTransportOverHTTP2 extends AbstractHttpClientTransport
{
    private final HTTP2Client client;
    private ClientConnectionFactory connectionFactory;
    private boolean useALPN = true;

    public HttpClientTransportOverHTTP2(HTTP2Client client)
    {
        this.client = client;
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            return new MultiplexConnectionPool(destination, httpClient.getMaxConnectionsPerDestination(), destination, httpClient.getMaxRequestsQueuedPerDestination());
        });
    }

    public HTTP2Client getHTTP2Client()
    {
        return client;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return client.getSelectors();
    }

    @ManagedAttribute(value = "Whether ALPN should be used when establishing connections")
    public boolean isUseALPN()
    {
        return useALPN;
    }

    public void setUseALPN(boolean useALPN)
    {
        this.useALPN = useALPN;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (!client.isStarted())
        {
            HttpClient httpClient = getHttpClient();
            client.setExecutor(httpClient.getExecutor());
            client.setScheduler(httpClient.getScheduler());
            client.setByteBufferPool(httpClient.getByteBufferPool());
            client.setConnectTimeout(httpClient.getConnectTimeout());
            client.setIdleTimeout(httpClient.getIdleTimeout());
            client.setInputBufferSize(httpClient.getResponseBufferSize());
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
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new HttpDestinationOverHTTP2(getHttpClient(), origin);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        HttpClient httpClient = getHttpClient();
        client.setConnectTimeout(httpClient.getConnectTimeout());
        client.setConnectBlocking(httpClient.isConnectBlocking());
        client.setBindAddress(httpClient.getBindAddress());

        SessionListenerPromise listenerPromise = new SessionListenerPromise(context);

        HttpDestinationOverHTTP2 destination = (HttpDestinationOverHTTP2)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        SslContextFactory sslContextFactory = null;
        if (HttpScheme.HTTPS.is(destination.getScheme()))
            sslContextFactory = httpClient.getSslContextFactory();

        connect(sslContextFactory, address, listenerPromise, listenerPromise, context);
    }

    protected void connect(SslContextFactory sslContextFactory, InetSocketAddress address, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        getHTTP2Client().connect(sslContextFactory, address, listener, promise, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        endPoint.setIdleTimeout(getHttpClient().getIdleTimeout());

        ClientConnectionFactory factory = connectionFactory;
        HttpDestinationOverHTTP2 destination = (HttpDestinationOverHTTP2)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        ProxyConfiguration.Proxy proxy = destination.getProxy();
        boolean ssl = proxy == null ? HttpScheme.HTTPS.is(destination.getScheme()) : proxy.isSecure();
        if (ssl && isUseALPN())
            factory = new ALPNClientConnectionFactory(client.getExecutor(), factory, client.getProtocols());
        return factory.newConnection(endPoint, context);
    }

    protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
    {
        return new HttpConnectionOverHTTP2(destination, session);
    }

    protected void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
    {
        connection.close();
    }

    private class SessionListenerPromise extends Session.Listener.Adapter implements Promise<Session>
    {
        private final AtomicMarkableReference<HttpConnectionOverHTTP2> connection = new AtomicMarkableReference<>(null, false);
        private final Map<String, Object> context;

        private SessionListenerPromise(Map<String, Object> context)
        {
            this.context = context;
        }

        @Override
        public void succeeded(Session session)
        {
            // This method is invoked when the client preface
            // is sent, but we want to succeed the nested
            // promise when the server preface is received.
        }

        @Override
        public void failed(Throwable failure)
        {
            failConnectionPromise(failure);
        }

        private HttpDestinationOverHTTP2 destination()
        {
            return (HttpDestinationOverHTTP2)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        }

        @SuppressWarnings("unchecked")
        private Promise<Connection> connectionPromise()
        {
            return (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        }

        @Override
        public void onSettings(Session session, SettingsFrame frame)
        {
            Map<Integer, Integer> settings = frame.getSettings();
            if (settings.containsKey(SettingsFrame.MAX_CONCURRENT_STREAMS))
                destination().setMaxRequestsPerConnection(settings.get(SettingsFrame.MAX_CONCURRENT_STREAMS));
            if (!connection.isMarked())
                onServerPreface(session);
        }

        private void onServerPreface(Session session)
        {
            HttpConnectionOverHTTP2 connection = newHttpConnection(destination(), session);
            if (this.connection.compareAndSet(null, connection, false, true))
                connectionPromise().succeeded(connection);
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame)
        {
            if (failConnectionPromise(new ClosedChannelException()))
                return;
            HttpConnectionOverHTTP2 connection = this.connection.getReference();
            if (connection != null)
                HttpClientTransportOverHTTP2.this.onClose(connection, frame);
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            long idleTimeout = ((HTTP2Session)session).getEndPoint().getIdleTimeout();
            if (failConnectionPromise(new TimeoutException("Idle timeout expired: " + idleTimeout + " ms")))
                return true;
            HttpConnectionOverHTTP2 connection = this.connection.getReference();
            if (connection != null)
                return connection.onIdleTimeout(idleTimeout);
            return true;
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            if (failConnectionPromise(failure))
                return;
            HttpConnectionOverHTTP2 connection = this.connection.getReference();
            if (connection != null)
                connection.close(failure);
        }

        private boolean failConnectionPromise(Throwable failure)
        {
            boolean result = connection.compareAndSet(null, null, false, true);
            if (result)
                connectionPromise().failed(failure);
            return result;
        }
    }
}
