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

package org.eclipse.jetty.http2.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.HTTP2Connection;
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
    private final HTTP2Client http2Client;
    private boolean useALPN = true;

    public HttpClientTransportOverHTTP2(HTTP2Client http2Client)
    {
        this.http2Client = http2Client;
        installBean(http2Client);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            return new MultiplexConnectionPool(destination, httpClient.getMaxConnectionsPerDestination(), 1);
        });
    }

    public HTTP2Client getHTTP2Client()
    {
        return http2Client;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return http2Client.getSelectors();
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
        if (!http2Client.isStarted())
            configure(getHttpClient(), getHTTP2Client());
        super.doStart();
    }

    static void configure(HttpClient httpClient, HTTP2Client http2Client)
    {
        http2Client.setExecutor(httpClient.getExecutor());
        http2Client.setScheduler(httpClient.getScheduler());
        http2Client.setByteBufferPool(httpClient.getByteBufferPool());
        http2Client.setConnectTimeout(httpClient.getConnectTimeout());
        http2Client.setIdleTimeout(httpClient.getIdleTimeout());
        http2Client.setInputBufferSize(httpClient.getResponseBufferSize());
        http2Client.setUseInputDirectByteBuffers(httpClient.isUseInputDirectByteBuffers());
        http2Client.setUseOutputDirectByteBuffers(httpClient.isUseOutputDirectByteBuffers());
        http2Client.setConnectBlocking(httpClient.isConnectBlocking());
        http2Client.setBindAddress(httpClient.getBindAddress());
        http2Client.setMaxResponseHeadersSize(httpClient.getMaxResponseHeadersSize());
    }

    @Override
    public Origin newOrigin(Request request)
    {
        String protocol = HttpScheme.HTTPS.is(request.getScheme()) ? "h2" : "h2c";
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of(protocol), false));
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        return new HttpDestination(getHttpClient(), origin);
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
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
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        getHTTP2Client().connect(destination.getOrigin().getTransport(), address, factory, listener, promise, context);
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
        Destination destination = (Destination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        ProxyConfiguration.Proxy proxy = destination.getProxy();
        boolean ssl = proxy == null ? destination.isSecure() : proxy.isSecure();
        if (ssl && isUseALPN())
            factory = new ALPNClientConnectionFactory(http2Client.getExecutor(), factory, http2Client.getProtocols());
        return factory.newConnection(endPoint, context);
    }

    protected Connection newConnection(Destination destination, Session session, HTTP2Connection connection)
    {
        return new HttpConnectionOverHTTP2(destination, session, connection);
    }

    protected void onClose(Connection connection, GoAwayFrame frame)
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
        protected Connection newConnection(Destination destination, Session session, HTTP2Connection connection)
        {
            return HttpClientTransportOverHTTP2.this.newConnection(destination, session, connection);
        }

        @Override
        public void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
        {
            HttpClientTransportOverHTTP2.this.onClose(connection, frame);
        }
    }
}
