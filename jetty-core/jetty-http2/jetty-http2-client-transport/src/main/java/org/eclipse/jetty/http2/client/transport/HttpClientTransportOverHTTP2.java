//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConnection;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.client.transport.internal.HTTPSessionListenerPromise;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("The HTTP/2 client transport")
public class HttpClientTransportOverHTTP2 extends AbstractHttpClientTransport
{
    private final ClientConnectionFactory connectionFactory = new HTTP2ClientConnectionFactory();
    private final HTTP2Client client;
    private boolean useALPN = true;

    public HttpClientTransportOverHTTP2(HTTP2Client client)
    {
        this.client = client;
        addBean(client.getClientConnector(), false);
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
            client.setUseInputDirectByteBuffers(httpClient.isUseInputDirectByteBuffers());
            client.setUseOutputDirectByteBuffers(httpClient.isUseOutputDirectByteBuffers());
            client.setConnectBlocking(httpClient.isConnectBlocking());
            client.setBindAddress(httpClient.getBindAddress());
        }
        addBean(client);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(client);
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        String protocol = HttpScheme.HTTPS.is(request.getScheme()) ? "h2" : "h2c";
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of(protocol), false));
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        SocketAddress address = origin.getAddress().getSocketAddress();
        return new MultiplexHttpDestination(getHttpClient(), origin, getHTTP2Client().getClientConnector().isIntrinsicallySecure(address));
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        HttpClient httpClient = getHttpClient();
        client.setConnectTimeout(httpClient.getConnectTimeout());
        client.setConnectBlocking(httpClient.isConnectBlocking());
        client.setBindAddress(httpClient.getBindAddress());

        SessionListenerPromise listenerPromise = new SessionListenerPromise(context);

        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        connect(address, destination.getClientConnectionFactory(), listenerPromise, listenerPromise, context);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        connect((SocketAddress)address, context);
    }

    protected void connect(SocketAddress address, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        getHTTP2Client().connect(address, factory, listener, promise, context);
    }

    protected void connect(InetSocketAddress address, ClientConnectionFactory factory, Session.Listener listener, Promise<Session> promise, Map<String, Object> context)
    {
        connect((SocketAddress)address, factory, listener, promise, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        endPoint.setIdleTimeout(getHttpClient().getIdleTimeout());

        ClientConnectionFactory factory = connectionFactory;
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        ProxyConfiguration.Proxy proxy = destination.getProxy();
        boolean ssl = proxy == null ? destination.isSecure() : proxy.isSecure();
        if (ssl && isUseALPN())
            factory = new ALPNClientConnectionFactory(client.getExecutor(), factory, client.getProtocols());
        return factory.newConnection(endPoint, context);
    }

    protected HttpConnection newHttpConnection(HttpDestination destination, Session session)
    {
        return new HttpConnectionOverHTTP2(destination, session);
    }

    protected void onClose(HttpConnection connection, GoAwayFrame frame)
    {
        connection.close();
    }

    private class SessionListenerPromise extends HTTPSessionListenerPromise
    {
        private SessionListenerPromise(Map<String, Object> context)
        {
            super(context);
        }

        @Override
        protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
        {
            return (HttpConnectionOverHTTP2)HttpClientTransportOverHTTP2.this.newHttpConnection(destination, session);
        }

        @Override
        public void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
        {
            HttpClientTransportOverHTTP2.this.onClose(connection, frame);
        }
    }
}
