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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.unixsocket.UnixSocketEndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

// TODO: this class needs a thorough review.
public class HttpClientTransportOverUnixSockets extends AbstractHttpClientTransport
{
    private static final Logger LOG = Log.getLogger(HttpClientTransportOverUnixSockets.class);

    private String _unixSocket;
    private SelectorManager selectorManager;

    private UnixSocketChannel channel;

    public HttpClientTransportOverUnixSockets(String unixSocket)
    {
        if (unixSocket == null)
            throw new IllegalArgumentException("Unix socket file cannot be null");
        this._unixSocket = unixSocket;
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            int maxConnections = httpClient.getMaxConnectionsPerDestination();
            return new DuplexConnectionPool(destination, maxConnections, destination);
        });
    }

    @Override
    protected void doStart() throws Exception
    {
        HttpClient httpClient = getHttpClient();
        selectorManager = new UnixSocketSelectorManager(httpClient, 1);
        selectorManager.setConnectTimeout(httpClient.getConnectTimeout());
        addBean(selectorManager);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        try
        {
            if (channel != null)
                channel.close();
        }
        catch (IOException xx)
        {
            LOG.ignore(xx);
        }
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new HttpDestinationOverHTTP(getHttpClient(), origin);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        try
        {
            InetAddress inet = address.getAddress();
            if (!inet.isLoopbackAddress() && !inet.isLinkLocalAddress() && !inet.isSiteLocalAddress())
                throw new IOException("UnixSocket cannot connect to " + address.getHostString());

            // Open a unix socket
            UnixSocketAddress unixAddress = new UnixSocketAddress(this._unixSocket);
            channel = UnixSocketChannel.open(unixAddress);

            HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
            HttpClient client = destination.getHttpClient();

            configure(client, channel);

            channel.configureBlocking(false);
            selectorManager.accept(channel, context);
        }
        // Must catch all exceptions, since some like
        // UnresolvedAddressException are not IOExceptions.
        catch (Throwable x)
        {
            try
            {
                if (channel != null)
                    channel.close();
            }
            catch (IOException xx)
            {
                LOG.ignore(xx);
            }
            finally
            {
                connectFailed(context, x);
            }
        }
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<org.eclipse.jetty.client.api.Connection> promise = (Promise<org.eclipse.jetty.client.api.Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        HttpConnectionOverHTTP connection = newHttpConnection(endPoint, destination, promise);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return customize(connection, context);
    }

    protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<org.eclipse.jetty.client.api.Connection> promise)
    {
        return new HttpConnectionOverHTTP(endPoint, destination, promise);
    }

    protected void configure(HttpClient client, SocketChannel channel) throws IOException
    {
        channel.socket().setTcpNoDelay(client.isTCPNoDelay());
    }

    public class UnixSocketSelectorManager extends SelectorManager
    {
        protected UnixSocketSelectorManager(HttpClient client, int selectors)
        {
            super(client.getExecutor(), client.getScheduler(), selectors);
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
            endPoint.setIdleTimeout(getHttpClient().getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
            return destination.getClientConnectionFactory().newConnection(endPoint, context);
        }

        @Override
        protected void connectionFailed(SelectableChannel channel, Throwable x, Object attachment)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            connectFailed(context, x);
        }
    }
}
