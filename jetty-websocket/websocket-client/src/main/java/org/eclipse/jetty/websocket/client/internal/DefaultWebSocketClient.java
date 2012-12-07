//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.client.masks.RandomMasker;
import org.eclipse.jetty.websocket.common.events.EventDriver;

/**
 * WebSocketClient for working with Upgrade (request and response), and establishing connections to the websocket URI of your choice.
 */
public class DefaultWebSocketClient extends FuturePromise<ClientUpgradeResponse> implements WebSocketClient
{
    private static final Logger LOG = Log.getLogger(DefaultWebSocketClient.class);

    private final WebSocketClientFactory factory;
    private final WebSocketPolicy policy;
    private final EventDriver websocket;
    private URI websocketUri;
    /**
     * The abstract WebSocketConnection in use and established for this client.
     * <p>
     * Note: this is intentionally kept neutral, as WebSocketClient must be able to handle connections that are either physical (a socket connection) or virtual
     * (eg: a mux connection).
     */
    private WebSocketConnection connection;
    private ClientUpgradeRequest upgradeRequest;
    private ClientUpgradeResponse upgradeResponse;
    private Masker masker;

    public DefaultWebSocketClient(WebSocketClientFactory factory, EventDriver websocket)
    {
        this.factory = factory;
        LOG.debug("factory.isRunning(): {}",factory.isRunning());
        LOG.debug("factory.isStarted(): {}",factory.isStarted());
        this.policy = factory.getPolicy();
        this.websocket = websocket;
        this.upgradeRequest = new ClientUpgradeRequest();
        this.masker = new RandomMasker();
    }

    @Override
    public Future<ClientUpgradeResponse> connect(URI websocketUri) throws IOException
    {
        if (!factory.isStarted())
        {
            throw new IllegalStateException(WebSocketClientFactory.class.getSimpleName() + " is not started");
        }

        // Validate websocket URI
        if (!websocketUri.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(websocketUri.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = websocketUri.getScheme().toLowerCase(Locale.ENGLISH);
        if (("ws".equals(scheme) == false) && ("wss".equals(scheme) == false))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        this.websocketUri = websocketUri;

        // Validate websocket URI
        Future<ClientUpgradeResponse> result = null;

        LOG.debug("connect({})",websocketUri);

        ConnectionManager manager = factory.getConnectionManager();
        // Check with factory first for possible alternate connect mechanism (such as mux)
        result = manager.connectVirtual(this);
        if (result == null)
        {
            // No such connect option, attempt to use a physical connection
            result = manager.connectPhysical(this);
        }

        return result;
    }

    @Override
    public void failed(Throwable cause)
    {
        LOG.debug("failed() - {}",cause);
        super.failed(cause);
    }

    protected ClientUpgradeRequest getClientUpgradeRequest()
    {
        return upgradeRequest;
    }

    public WebSocketConnection getConnection()
    {
        return this.connection;
    }

    @Override
    public WebSocketClientFactory getFactory()
    {
        return factory;
    }

    @Override
    public Masker getMasker()
    {
        return masker;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    @Override
    public ClientUpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
    }

    @Override
    public ClientUpgradeResponse getUpgradeResponse()
    {
        return upgradeResponse;
    }

    @Override
    public EventDriver getWebSocket()
    {
        return websocket;
    }

    @Override
    public URI getWebSocketUri()
    {
        return websocketUri;
    }

    @Override
    public void setMasker(Masker masker)
    {
        this.masker = masker;
    }

    public void setUpgradeResponse(ClientUpgradeResponse response)
    {
        this.upgradeResponse = response;
    }

    @Override
    public void succeeded(ClientUpgradeResponse response)
    {
        LOG.debug("completed() - {}",response);
        super.succeeded(response);
    }
}
