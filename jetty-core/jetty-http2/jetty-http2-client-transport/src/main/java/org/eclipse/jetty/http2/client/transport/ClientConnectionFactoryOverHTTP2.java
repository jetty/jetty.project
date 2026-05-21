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
import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.client.transport.internal.HTTPSessionListenerPromise;
import org.eclipse.jetty.http2.client.transport.internal.HttpConnectionOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class ClientConnectionFactoryOverHTTP2 extends ContainerLifeCycle implements ClientConnectionFactory, HttpClient.Aware
{
    private final ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();
    private final HTTP2Client http2Client;

    public ClientConnectionFactoryOverHTTP2(HTTP2Client http2Client)
    {
        this.http2Client = http2Client;
        installBean(http2Client);
    }

    @Override
    public void setHttpClient(HttpClient httpClient)
    {
        HttpClientTransportOverHTTP2.configure(httpClient, http2Client);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HTTPSessionListenerPromise listenerPromise = new HTTPSessionListenerPromise(context);
        context.put(HTTP2Client.CONTEXT_KEY, http2Client);
        context.put(HTTP2Client.SESSION_LISTENER_CONTEXT_KEY, listenerPromise);
        context.put(HTTP2Client.SESSION_PROMISE_CONTEXT_KEY, listenerPromise);
        return factory.newConnection(endPoint, context);
    }

    /**
     * <p>Representation of the {@code HTTP/2} application protocol used by {@link HttpClientTransportDynamic}.</p>
     *
     * @see HttpClientConnectionFactory#HTTP11
     */
    public static class HTTP2 extends Info
    {
        private final List<String> protocols;

        public HTTP2(HTTP2Client http2Client)
        {
            this(http2Client, List.of("h2", "h2c"));
        }

        public HTTP2(HTTP2Client http2Client, List<String> protocols)
        {
            super(new ClientConnectionFactoryOverHTTP2(http2Client));
            this.protocols = protocols;
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            if (secure)
                return protocols;
            return protocols.stream()
                .filter(Predicate.not("h2"::equals))
                .toList();
        }

        @Override
        public void upgrade(EndPoint endPoint, Map<String, Object> context)
        {
            HttpDestination destination = (HttpDestination)context.get(Destination.CONTEXT_KEY);
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(Connection.PROMISE_CONTEXT_KEY);
            context.put(Connection.PROMISE_CONTEXT_KEY, new Promise<HttpConnectionOverHTTP2>()
            {
                @Override
                public void succeeded(HttpConnectionOverHTTP2 connection)
                {
                    // This code is run when the client receives the server preface reply.
                    // Upgrade the connection to setup HTTP/2 frame listeners that will
                    // handle the HTTP/2 response to the upgrade request.

                    if (connection.upgrade(context))
                    {
                        // The connection can be used only after the upgrade that
                        // creates stream #1 corresponding to the HTTP/1.1 upgrade
                        // request, otherwise other requests can steal id #1.
                        destination.accept(connection);
                        promise.succeeded(connection);
                    }
                    else
                    {
                        connection.close();
                        promise.failed(new ClosedChannelException());
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    promise.failed(x);
                }
            });
            // The EndPoint is the one below the protocol Connection,
            // so in case of secure communication is the SslEndPoint.
            // Keep the existing SslConnection that has already performed
            // the TLS handshake, and just upgrade the nested connection.
            upgrade(destination.resolveClientConnectionFactory(), endPoint, context);
        }

        private void upgrade(ClientConnectionFactory factory, EndPoint endPoint, Map<String, Object> context)
        {
            try
            {
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
            return String.format("%s@%x%s", TypeUtil.toShortName(getClass()), hashCode(), protocols);
        }
    }
}
