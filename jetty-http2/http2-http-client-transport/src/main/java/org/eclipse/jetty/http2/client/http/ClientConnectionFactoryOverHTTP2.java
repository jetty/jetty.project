//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class ClientConnectionFactoryOverHTTP2 extends ContainerLifeCycle implements ClientConnectionFactory
{
    private final ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();
    private final HTTP2Client client;

    public ClientConnectionFactoryOverHTTP2(HTTP2Client client)
    {
        this.client = client;
        addBean(client);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HTTPSessionListenerPromise listenerPromise = new HTTPSessionListenerPromise(context);
        context.put(HTTP2ClientConnectionFactory.CLIENT_CONTEXT_KEY, client);
        context.put(HTTP2ClientConnectionFactory.SESSION_LISTENER_CONTEXT_KEY, listenerPromise);
        context.put(HTTP2ClientConnectionFactory.SESSION_PROMISE_CONTEXT_KEY, listenerPromise);
        return factory.newConnection(endPoint, context);
    }

    /**
     * <p>Representation of the {@code HTTP/2} application protocol used by {@link HttpClientTransportDynamic}.</p>
     *
     * @see HttpClientConnectionFactory#HTTP11
     */
    public static class HTTP2 extends Info
    {
        private static final List<String> protocols = List.of("h2", "h2c");
        private static final List<String> h2c = List.of("h2c");

        public HTTP2(HTTP2Client client)
        {
            super(new ClientConnectionFactoryOverHTTP2(client));
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            return secure ? protocols : h2c;
        }

        @Override
        public void upgrade(EndPoint endPoint, Map<String, Object> context)
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, new Promise<HttpConnectionOverHTTP2>()
            {
                @Override
                public void succeeded(HttpConnectionOverHTTP2 connection)
                {
                    // This code is run when the client receives the server preface reply.
                    // Upgrade the connection to setup HTTP/2 frame listeners that will
                    // handle the HTTP/2 response to the upgrade request.
                    promise.succeeded(connection);
                    connection.upgrade(context);
                    // The connection can be used only after the upgrade that
                    // creates stream #1 corresponding to the HTTP/1.1 upgrade
                    // request, otherwise other requests can steal id #1.
                    destination.accept(connection);
                }

                @Override
                public void failed(Throwable x)
                {
                    promise.failed(x);
                }
            });
            upgrade(destination.getClientConnectionFactory(), endPoint, context);
        }

        private void upgrade(ClientConnectionFactory factory, EndPoint endPoint, Map<String, Object> context)
        {
            try
            {
                // Avoid double TLS wrapping. We want to keep the existing
                // SslConnection that has already performed the TLS handshake,
                // and just upgrade the nested connection.
                if (factory instanceof SslClientConnectionFactory && endPoint instanceof SslConnection.DecryptedEndPoint)
                    factory = ((SslClientConnectionFactory)factory).getClientConnectionFactory();
                var newConnection = factory.newConnection(endPoint, context);
                endPoint.upgrade(newConnection);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x%s", getClass().getSimpleName(), hashCode(), protocols);
        }
    }
}
