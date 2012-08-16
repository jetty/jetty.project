// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;

public class WebSocketClient
{
    public static class ConnectFuture extends FutureCallback<WebSocketConnection>
    {
        private static final Logger LOG = Log.getLogger(ConnectFuture.class);
        private final WebSocketClient client;
        private final URI websocketUri;
        private final WebSocketEventDriver websocket;

        public ConnectFuture(WebSocketClient client, URI websocketUri, WebSocketEventDriver websocket)
        {
            this.client = client;
            this.websocketUri = websocketUri;
            this.websocket = websocket;
        }

        @Override
        public void completed(WebSocketConnection context)
        {
            LOG.debug("completed() - {}",context);
            // TODO Auto-generated method stub
            super.completed(context);
        }

        @Override
        public void failed(WebSocketConnection context, Throwable cause)
        {
            LOG.debug("failed() - {}, {}",context,cause);
            LOG.info(cause);
            // TODO Auto-generated method stub
            super.failed(context,cause);
        }

        public WebSocketClient getClient()
        {
            return client;
        }

        public Map<String, String> getCookies()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public WebSocketClientFactory getFactory()
        {
            return client.factory;
        }

        public String getOrigin()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public WebSocketEventDriver getWebSocket()
        {
            return websocket;
        }

        public URI getWebSocketUri()
        {
            return websocketUri;
        }
    }

    private static final Logger LOG = Log.getLogger(WebSocketClient.class);

    public static InetSocketAddress toSocketAddress(URI uri)
    {
        return new InetSocketAddress(uri.getHost(),uri.getPort());
    }

    private final WebSocketClientFactory factory;
    private SocketAddress bindAddress;
    private WebSocketPolicy policy;

    public WebSocketClient(WebSocketClientFactory factory)
    {
        this.factory = factory;
        this.policy = WebSocketPolicy.newClientPolicy();
    }

    public Future<WebSocketConnection> connect(URI websocketUri, Object websocketPojo) throws IOException
    {
        if (!factory.isStarted())
        {
            throw new IllegalStateException(WebSocketClientFactory.class.getSimpleName() + " is not started");
        }

        SocketChannel channel = SocketChannel.open();
        if (bindAddress != null)
        {
            channel.bind(bindAddress);
        }

        channel.socket().setTcpNoDelay(true); // disable nagle
        channel.configureBlocking(false); // async all the way

        InetSocketAddress address = toSocketAddress(websocketUri);
        LOG.debug("Connect to {}",address);

        WebSocketEventDriver websocket = this.factory.newWebSocketDriver(websocketPojo);
        ConnectFuture result = new ConnectFuture(this,websocketUri,websocket);

        channel.connect(address);
        factory.getSelector().connect(channel,result);

        return result;
    }

    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    /**
     * @param bindAddress
     *            the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public void setProtocol(String protocol)
    {
        // TODO Auto-generated method stub
    }
}
