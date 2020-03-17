//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyProtocolClientConnectionFactory implements ClientConnectionFactory
{
    private final ClientConnectionFactory connectionFactory;
    private final Supplier<Origin.Address> proxiedAddressSupplier;

    public ProxyProtocolClientConnectionFactory(ClientConnectionFactory connectionFactory, Supplier<Origin.Address> proxiedAddressSupplier)
    {
        this.connectionFactory = connectionFactory;
        this.proxiedAddressSupplier = proxiedAddressSupplier;
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
        Executor executor = destination.getHttpClient().getExecutor();
        ProxyProtocolConnection connection = new ProxyProtocolConnection(endPoint, executor, context);
        return customize(connection, context);
    }

    private class ProxyProtocolConnection extends AbstractConnection implements Callback
    {
        private final Logger log = LoggerFactory.getLogger(ProxyProtocolConnection.class);
        private final Map<String, Object> context;

        public ProxyProtocolConnection(EndPoint endPoint, Executor executor, Map<String, Object> context)
        {
            super(endPoint, executor);
            this.context = context;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            writePROXYLine();
        }

        // @checkstyle-disable-check : MethodNameCheck

        protected void writePROXYLine()
        {
            Origin.Address proxiedAddress = proxiedAddressSupplier.get();
            if (proxiedAddress == null)
            {
                failed(new IllegalArgumentException("Missing proxied socket address"));
                return;
            }
            String proxiedIP = proxiedAddress.getHost();
            int proxiedPort = proxiedAddress.getPort();
            InetSocketAddress serverSocketAddress = getEndPoint().getRemoteAddress();
            InetAddress serverAddress = serverSocketAddress.getAddress();
            String serverIP = serverAddress.getHostAddress();
            int serverPort = serverSocketAddress.getPort();

            boolean ipv6 = serverAddress instanceof Inet6Address;
            String line = String.format("PROXY %s %s %s %d %d\r\n", ipv6 ? "TCP6" : "TCP4", proxiedIP, serverIP, proxiedPort, serverPort);
            if (log.isDebugEnabled())
                log.debug("Writing PROXY line: {}", line.trim());
            ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII));
            getEndPoint().write(this, buffer);
        }

        // @checkstyle-enable-check : MethodNameCheck

        @Override
        public void succeeded()
        {
            try
            {
                EndPoint endPoint = getEndPoint();
                org.eclipse.jetty.io.Connection connection = connectionFactory.newConnection(endPoint, context);
                if (log.isDebugEnabled())
                    log.debug("Written PROXY line, upgrading to {}", connection);
                endPoint.upgrade(connection);
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            promise.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void onFillable()
        {
        }
    }
}
