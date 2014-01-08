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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class AbstractHttpClientTransport extends ContainerLifeCycle implements HttpClientTransport
{
    protected static final Logger LOG = Log.getLogger(HttpClientTransport.class);

    private final int selectors;
    private volatile HttpClient client;
    private volatile SelectorManager selectorManager;

    protected AbstractHttpClientTransport(int selectors)
    {
        this.selectors = selectors;
    }

    protected HttpClient getHttpClient()
    {
        return client;
    }

    @Override
    public void setHttpClient(HttpClient client)
    {
        this.client = client;
    }

    @Override
    protected void doStart() throws Exception
    {
        selectorManager = newSelectorManager(client);
        selectorManager.setConnectTimeout(client.getConnectTimeout());
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
    public void connect(SocketAddress address, Map<String, Object> context)
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
            channel.configureBlocking(false);
            channel.connect(address);

            context.put(SslClientConnectionFactory.SSL_PEER_HOST_CONTEXT_KEY, destination.getHost());
            context.put(SslClientConnectionFactory.SSL_PEER_PORT_CONTEXT_KEY, destination.getPort());
            selectorManager.connect(channel, context);
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
        return new ClientSelectorManager(client, selectors);
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
        protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key)
        {
            return new SelectChannelEndPoint(channel, selector, key, getScheduler(), client.getIdleTimeout());
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
            return destination.getClientConnectionFactory().newConnection(endPoint, context);
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable x, Object attachment)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            connectFailed(context, x);
        }
    }
}
