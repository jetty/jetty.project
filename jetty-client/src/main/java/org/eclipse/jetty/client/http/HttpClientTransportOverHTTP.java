//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HttpClientTransportOverHTTP extends ContainerLifeCycle implements HttpClientTransport
{
    private static final Logger LOG = Log.getLogger(HttpClientTransportOverHTTP.class);

    private volatile HttpClient client;
    private volatile SelectorManager selectorManager;

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
    public HttpDestination newHttpDestination(HttpClient client, String scheme, String host, int port)
    {
        return new HttpDestinationOverHTTP(client, scheme, host, port);
    }

    @Override
    public void connect(HttpDestination destination, SocketAddress address, Promise<org.eclipse.jetty.client.api.Connection> promise)
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            HttpClient client = destination.getHttpClient();
            SocketAddress bindAddress = client.getBindAddress();
            if (bindAddress != null)
                channel.bind(bindAddress);
            configure(client, channel);
            channel.configureBlocking(false);
            channel.connect(address);

            ConnectionCallback callback = new ConnectionCallback(destination, promise);
            selectorManager.connect(channel, callback);
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
                promise.failed(x);
            }
        }
    }

    protected void configure(HttpClient client, SocketChannel channel) throws SocketException
    {
        channel.socket().setTcpNoDelay(client.isTCPNoDelay());
    }

    protected SelectorManager newSelectorManager(HttpClient client)
    {
        return new ClientSelectorManager(client, 1);
    }

    protected Connection newHttpConnection(HttpClient httpClient, EndPoint endPoint, HttpDestination destination)
    {
        return new HttpConnectionOverHTTP(httpClient, endPoint, destination);
    }

    protected SslConnection newSslConnection(HttpClient httpClient, EndPoint endPoint, SSLEngine engine)
    {
        return new SslConnection(httpClient.getByteBufferPool(), httpClient.getExecutor(), endPoint, engine);
    }

    private SslConnection createSslConnection(HttpDestination destination, EndPoint endPoint)
    {
        HttpClient httpClient = destination.getHttpClient();
        SslContextFactory sslContextFactory = httpClient.getSslContextFactory();
        SSLEngine engine = sslContextFactory.newSSLEngine(destination.getHost(), destination.getPort());
        engine.setUseClientMode(true);

        SslConnection sslConnection = newSslConnection(httpClient, endPoint, engine);
        sslConnection.setRenegotiationAllowed(sslContextFactory.isRenegotiationAllowed());
        endPoint.setConnection(sslConnection);
        EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
        Connection connection = newHttpConnection(httpClient, appEndPoint, destination);
        appEndPoint.setConnection(connection);

        return sslConnection;
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
        public Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            HttpDestination destination = callback.destination;

            SslContextFactory sslContextFactory = client.getSslContextFactory();
            if (!destination.isProxied() && HttpScheme.HTTPS.is(destination.getScheme()))
            {
                if (sslContextFactory == null)
                {
                    IOException failure = new ConnectException("Missing " + SslContextFactory.class.getSimpleName() + " for " + destination.getScheme() + " requests");
                    callback.failed(failure);
                    throw failure;
                }
                else
                {
                    SslConnection sslConnection = createSslConnection(destination, endPoint);
                    callback.succeeded((org.eclipse.jetty.client.api.Connection)sslConnection.getDecryptedEndPoint().getConnection());
                    return sslConnection;
                }
            }
            else
            {
                Connection connection = newHttpConnection(client, endPoint, destination);
                callback.succeeded((org.eclipse.jetty.client.api.Connection)connection);
                return connection;
            }
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            ConnectionCallback callback = (ConnectionCallback)attachment;
            callback.failed(ex);
        }
    }

    private class ConnectionCallback implements Promise<org.eclipse.jetty.client.api.Connection>
    {
        private final HttpDestination destination;
        private final Promise<org.eclipse.jetty.client.api.Connection> promise;

        private ConnectionCallback(HttpDestination destination, Promise<org.eclipse.jetty.client.api.Connection> promise)
        {
            this.destination = destination;
            this.promise = promise;
        }

        @Override
        public void succeeded(org.eclipse.jetty.client.api.Connection result)
        {
            promise.succeeded(result);
        }

        @Override
        public void failed(Throwable x)
        {
            promise.failed(x);
        }
    }
}
