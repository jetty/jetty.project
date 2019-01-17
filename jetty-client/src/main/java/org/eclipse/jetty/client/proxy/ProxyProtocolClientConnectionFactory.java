//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ProxyProtocolClientConnectionFactory implements ClientConnectionFactory
{
    private final ClientConnectionFactory connectionFactory;
    private final Supplier<InetSocketAddress> proxiedAddressSupplier;

    public ProxyProtocolClientConnectionFactory(ClientConnectionFactory connectionFactory, Supplier<InetSocketAddress> proxiedAddressSupplier)
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
        private final Logger LOG = Log.getLogger(ProxyProtocolConnection.class);
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

        protected void writePROXYLine()
        {
            InetSocketAddress proxiedSocketAddress = proxiedAddressSupplier.get();
            if (proxiedSocketAddress == null)
            {
                failed(new IllegalArgumentException("Missing proxied socket address"));
                return;
            }
            InetAddress proxiedAddress = proxiedSocketAddress.getAddress();
            if (proxiedAddress == null)
            {
                failed(new IllegalArgumentException("Unresolved proxied socket address " + proxiedSocketAddress));
                return;
            }

            String proxiedIP = proxiedAddress.getHostAddress();
            int proxiedPort = proxiedSocketAddress.getPort();
            InetSocketAddress serverSocketAddress = getEndPoint().getRemoteAddress();
            InetAddress serverAddress = serverSocketAddress.getAddress();
            String serverIP = serverAddress.getHostAddress();
            int serverPort = serverSocketAddress.getPort();

            boolean ipv6 = proxiedAddress instanceof Inet6Address && serverAddress instanceof Inet6Address;
            String line = String.format("PROXY %s %s %s %d %d\r\n", ipv6 ? "TCP6" : "TCP4" , proxiedIP, serverIP, proxiedPort, serverPort);
            if (LOG.isDebugEnabled())
                LOG.debug("Writing PROXY line: {}", line.trim());
            ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.US_ASCII));
            getEndPoint().write(this, buffer);
        }

        @Override
        public void succeeded()
        {
            try
            {
                EndPoint endPoint = getEndPoint();
                org.eclipse.jetty.io.Connection connection = connectionFactory.newConnection(endPoint, context);
                if (LOG.isDebugEnabled())
                    LOG.debug("Written PROXY line, upgrading to {}", connection);
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
