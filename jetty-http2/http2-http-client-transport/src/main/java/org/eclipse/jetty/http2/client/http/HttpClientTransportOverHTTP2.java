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
import java.net.SocketAddress;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class HttpClientTransportOverHTTP2 extends ContainerLifeCycle implements HttpClientTransport
{
    private final HTTP2Client client;
    private HttpClient httpClient;

    public HttpClientTransportOverHTTP2(HTTP2Client client)
    {
        this.client = client;
    }

    @Override
    protected void doStart() throws Exception
    {
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
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        client.setConnectTimeout(httpClient.getConnectTimeout());

        final HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        final Promise<Connection> connection = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);

        Session.Listener listener = new Session.Listener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                destination.abort(failure);
            }
        };

        final Promise<Session> promise = new Promise<Session>()
        {
            @Override
            public void succeeded(Session result)
            {
                connection.succeeded(new HttpConnectionOverHTTP2(destination, result));
            }

            @Override
            public void failed(Throwable failure)
            {
                connection.failed(failure);
            }
        };

        client.connect(httpClient.getSslContextFactory(), (InetSocketAddress)address, listener, promise, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        final HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        return destination.getClientConnectionFactory().newConnection(endPoint, context);
    }
}
