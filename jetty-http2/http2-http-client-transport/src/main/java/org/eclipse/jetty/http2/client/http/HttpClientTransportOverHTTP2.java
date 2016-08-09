//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
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
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@ManagedObject("The HTTP/2 client transport")
public class HttpClientTransportOverHTTP2 extends ContainerLifeCycle implements HttpClientTransport
{
    private final HTTP2Client client;
    private ClientConnectionFactory connectionFactory;
    private HttpClient httpClient;
    private boolean useALPN = true;

    public HttpClientTransportOverHTTP2(HTTP2Client client)
    {
        this.client = client;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return client.getSelectors();
    }

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

    protected HttpClient getHttpClient()
    {
        return httpClient;
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

        SessionListenerPromise listenerPromise = new SessionListenerPromise(context);

        HttpDestinationOverHTTP2 destination = (HttpDestinationOverHTTP2)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        SslContextFactory sslContextFactory = null;
        if (HttpScheme.HTTPS.is(destination.getScheme()))
            sslContextFactory = httpClient.getSslContextFactory();

        client.connect(sslContextFactory, address, listenerPromise, listenerPromise, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        endPoint.setIdleTimeout(httpClient.getIdleTimeout());

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
        private final Map<String, Object> context;
        private HttpConnectionOverHTTP2 connection;

        private SessionListenerPromise(Map<String, Object> context)
        {
            this.context = context;
        }

        @Override
        public void succeeded(Session session)
        {
            connection = newHttpConnection(destination(), session);
            promise().succeeded(connection);
        }

        @Override
        public void failed(Throwable failure)
        {
            promise().failed(failure);
        }

        private HttpDestinationOverHTTP2 destination()
        {
            return (HttpDestinationOverHTTP2)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        }

        @SuppressWarnings("unchecked")
        private Promise<Connection> promise()
        {
            return (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        }

        @Override
        public Map<Integer, Integer> onPreface(Session session)
        {
            Map<Integer, Integer> settings = new HashMap<>();
            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, client.getInitialStreamRecvWindow());
            return settings;
        }

        @Override
        public void onSettings(Session session, SettingsFrame frame)
        {
            Map<Integer, Integer> settings = frame.getSettings();
            if (settings.containsKey(SettingsFrame.MAX_CONCURRENT_STREAMS))
                destination().setMaxRequestsPerConnection(settings.get(SettingsFrame.MAX_CONCURRENT_STREAMS));
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame)
        {
            HttpClientTransportOverHTTP2.this.onClose(connection, frame);
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            return connection.onIdleTimeout(((HTTP2Session)session).getEndPoint().getIdleTimeout());
        }

        @Override
        public void onFailure(Session session, Throwable failure)
        {
            HttpConnectionOverHTTP2 c = connection;
            if (c != null)
                c.close(failure);
        }
    }
}
