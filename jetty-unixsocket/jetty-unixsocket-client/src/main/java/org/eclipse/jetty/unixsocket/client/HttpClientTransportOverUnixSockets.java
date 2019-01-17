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

package org.eclipse.jetty.unixsocket.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.Executor;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.DuplexHttpDestination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.unixsocket.common.UnixSocketEndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

// TODO: this class needs a thorough review.
public class HttpClientTransportOverUnixSockets extends AbstractConnectorHttpClientTransport
{
    private static final Logger LOG = Log.getLogger(HttpClientTransportOverUnixSockets.class);

    public HttpClientTransportOverUnixSockets(String unixSocket)
    {
        this(new UnixSocketClientConnector(unixSocket));
    }

    private HttpClientTransportOverUnixSockets(ClientConnector connector)
    {
        super(connector);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            int maxConnections = httpClient.getMaxConnectionsPerDestination();
            return new DuplexConnectionPool(destination, maxConnections, destination);
        });
    }

    @Override
    public HttpDestination newHttpDestination(HttpDestination.Info info)
    {
        return new DuplexHttpDestination(getHttpClient(), info);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        org.eclipse.jetty.io.Connection connection = newHttpConnection(endPoint, destination, promise);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return customize(connection, context);
    }

    protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<org.eclipse.jetty.client.api.Connection> promise)
    {
        return new HttpConnectionOverHTTP(endPoint, destination, promise);
    }

    private static class UnixSocketClientConnector extends ClientConnector
    {
        private final String unixSocket;

        private UnixSocketClientConnector(String unixSocket)
        {
            this.unixSocket = unixSocket;
        }

        @Override
        protected SelectorManager newSelectorManager()
        {
            return new UnixSocketSelectorManager(getExecutor(), getScheduler(), getSelectors());
        }

        @Override
        public void connect(SocketAddress address, Map<String, Object> context)
        {
            InetSocketAddress socketAddress = (InetSocketAddress)address;
            InetAddress inetAddress = socketAddress.getAddress();
            if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress() || inetAddress.isSiteLocalAddress())
            {
                SocketChannel channel = null;
                try
                {
                    UnixSocketAddress unixAddress = new UnixSocketAddress(unixSocket);
                    channel = UnixSocketChannel.open(unixAddress);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Created {} for {}", channel, unixAddress);
                    accept(channel, context);
                }
                catch (Throwable x)
                {
                    safeClose(channel);
                    connectFailed(x, context);
                }
            }
            else
            {
                connectFailed(new ConnectException("UnixSocket cannot connect to " + socketAddress.getHostString()), context);
            }
        }

        private class UnixSocketSelectorManager extends ClientSelectorManager
        {
            private UnixSocketSelectorManager(Executor executor, Scheduler scheduler, int selectors)
            {
                super(executor, scheduler, selectors);
            }

            @Override
            protected Selector newSelector() throws IOException
            {
                return NativeSelectorProvider.getInstance().openSelector();
            }

            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
            {
                UnixSocketEndPoint endPoint = new UnixSocketEndPoint((UnixSocketChannel)channel, selector, key, getScheduler());
                endPoint.setIdleTimeout(getIdleTimeout().toMillis());
                return endPoint;
            }
        }
    }
}
