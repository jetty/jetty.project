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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.DuplexHttpDestination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.unixsocket.common.UnixSocketEndPoint;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @deprecated use any {@link HttpClientTransport} with {@link ClientConnector#forUnixDomain(Path)} instead (requires Java 16 or later)
 */
@Deprecated(forRemoval = true)
public class HttpClientTransportOverUnixSockets extends AbstractConnectorHttpClientTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransportOverUnixSockets.class);

    private final ClientConnectionFactory factory = new HttpClientConnectionFactory();

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
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, null);
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new DuplexHttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        var connection = factory.newConnection(endPoint, context);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return connection;
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
                    IO.close(channel);
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
