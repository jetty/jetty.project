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

package org.eclipse.jetty.spdy.client.http;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.client.SPDYClient;
import org.eclipse.jetty.util.Promise;

public class HttpClientTransportOverSPDY implements HttpClientTransport
{
    private final SPDYClient client;
    private final ClientConnectionFactory connectionFactory;
    private HttpClient httpClient;

    public HttpClientTransportOverSPDY(SPDYClient client)
    {
        this.client = client;
        this.connectionFactory = client.getClientConnectionFactory();
        client.setClientConnectionFactory(new ClientConnectionFactory()
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
            {
                HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
                return destination.getClientConnectionFactory().newConnection(endPoint, context);
            }
        });
    }

    @Override
    public void setHttpClient(HttpClient client)
    {
        httpClient = client;
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new HttpDestinationOverSPDY(httpClient, origin);
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        final HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        final Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);

        SessionFrameListener.Adapter listener = new SessionFrameListener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable x)
            {
                destination.abort(x);
            }
        };

        client.connect(address, listener, new Promise<Session>()
        {
            @Override
            public void succeeded(Session session)
            {
                promise.succeeded(new HttpConnectionOverSPDY(destination, session));
            }

            @Override
            public void failed(Throwable x)
            {
                promise.failed(x);
            }
        }, context);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        return connectionFactory.newConnection(endPoint, context);
    }
}
