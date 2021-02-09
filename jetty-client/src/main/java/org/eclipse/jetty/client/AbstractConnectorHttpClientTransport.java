//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public abstract class AbstractConnectorHttpClientTransport extends AbstractHttpClientTransport
{
    private final int selectors;
    private SelectorManager selectorManager;

    protected AbstractConnectorHttpClientTransport(int selectors)
    {
        this.selectors = selectors;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return selectors;
    }

    @Override
    protected void doStart() throws Exception
    {
        HttpClient httpClient = getHttpClient();
        selectorManager = newSelectorManager(httpClient);
        selectorManager.setConnectTimeout(httpClient.getConnectTimeout());
        addBean(selectorManager);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(selectorManager);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
            HttpClient client = destination.getHttpClient();
            SocketAddress bindAddress = client.getBindAddress();
            if (bindAddress != null)
                channel.bind(bindAddress);
            configure(client, channel);

            context.put(SslClientConnectionFactory.SSL_PEER_HOST_CONTEXT_KEY, address.getHostString());
            context.put(SslClientConnectionFactory.SSL_PEER_PORT_CONTEXT_KEY, address.getPort());

            boolean connected = true;
            if (client.isConnectBlocking())
            {
                channel.socket().connect(address, (int)client.getConnectTimeout());
                channel.configureBlocking(false);
            }
            else
            {
                channel.configureBlocking(false);
                connected = channel.connect(address);
            }
            if (connected)
                selectorManager.accept(channel, context);
            else
                selectorManager.connect(channel, context);
        }
        // Must catch all exceptions, since some like
        // UnresolvedAddressException are not IOExceptions.
        catch (Throwable x)
        {
            // If IPv6 is not deployed, a generic SocketException "Network is unreachable"
            // exception is being thrown, so we attempt to provide a better error message.
            if (x.getClass() == SocketException.class)
                x = new SocketException("Could not connect to " + address).initCause(x);

            try
            {
                if (channel != null)
                    channel.close();
            }
            catch (IOException ignored)
            {
                LOG.ignore(ignored);
            }
            finally
            {
                connectFailed(context, x);
            }
        }
    }

    protected void connectFailed(Map<String, Object> context, Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Could not connect to {}", context.get(HTTP_DESTINATION_CONTEXT_KEY));
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        promise.failed(x);
    }

    protected void configure(HttpClient client, SocketChannel channel) throws IOException
    {
        channel.socket().setTcpNoDelay(client.isTCPNoDelay());
    }

    protected SelectorManager newSelectorManager(HttpClient client)
    {
        return new ClientSelectorManager(client, getSelectors());
    }

    protected SelectorManager getSelectorManager()
    {
        return selectorManager;
    }

    protected class ClientSelectorManager extends SelectorManager
    {
        private final HttpClient client;

        protected ClientSelectorManager(HttpClient client, int selectors)
        {
            super(client.getExecutor(), client.getScheduler(), selectors);
            this.client = client;
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
        {
            SocketChannelEndPoint endp = new SocketChannelEndPoint(channel, selector, key, getScheduler());
            endp.setIdleTimeout(client.getIdleTimeout());
            return endp;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment) throws IOException
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
